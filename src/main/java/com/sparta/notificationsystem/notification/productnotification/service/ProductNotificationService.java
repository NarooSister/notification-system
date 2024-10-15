package com.sparta.notificationsystem.notification.productnotification.service;

import com.sparta.notificationsystem.notification.productnotification.entity.ProductNotificationHistory;
import com.sparta.notificationsystem.notification.productnotification.repository.ProductUserNotificationRepository;
import com.sparta.notificationsystem.notification.productusernotification.entity.ProductUserNotification;
import com.sparta.notificationsystem.notification.productusernotification.entity.ProductUserNotificationHistory;
import com.sparta.notificationsystem.notification.productusernotification.repository.ProductNotificationHistoryRepository;
import com.sparta.notificationsystem.notification.productusernotification.repository.ProductUserNotificationHistoryRepository;
import com.sparta.notificationsystem.product.entity.Product;
import com.sparta.notificationsystem.product.repository.ProductRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductNotificationService {
    private final Sinks.Many<String> sink;
    private final Flux<String> restockNotificationStream;
    private final ProductRepository productRepository;
    private final ProductUserNotificationRepository productUserNotificationRepository;
    private final ProductUserNotificationHistoryRepository productUserNotificationHistoryRepository;
    private final ProductNotificationHistoryRepository productNotificationHistoryRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    // 알림 프로세스
    @Transactional
    public Mono<Boolean> processRestockNotification(Long productId) {
        return fetchProductAndStock(productId)        // 1. Product 및 stock 상태 확인
                .flatMap(this::notifyUsersAndHandleStock)  // 2. 알림 전송 및 재고 상태 처리
                .subscribeOn(Schedulers.boundedElastic())  // 3. 비동기 실행
                .onErrorResume(throwable -> handleProcessError(productId, throwable));  // 4. 오류 처리
    }

    // 수동 알림 프로세스
    @Transactional
    public Mono<Boolean> processRestockNotificationManual(Long productId) {
        return fetchProductAndStock(productId)
                .flatMap(product -> {
                    // 이전 알림 목록 확인
                    ProductNotificationHistory lastNotificationHistory = getLastNotificationHistory();

                    // 취소된 알림이 있는지 확인하고 없으면 예외 발생
                    if (lastNotificationHistory == null || !isLastNotificationFailed(lastNotificationHistory)) {
                        return Mono.error(new NoSuchElementException("에러나 품절로 인해 취소된 알림이 없습니다."));
                    }

                    // 취소된 알림이 있으면, 취소된 알림 이후의 유저에게만 알림 전송
                    List<Long> notificationUserIds = getRemainingNotificationsFromCacheOrDB(productId, lastNotificationHistory.getLastUserId());

                    return sendNotificationAndSaveHistory(product, notificationUserIds);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(throwable -> handleProcessError(productId, throwable));
    }
    private Mono<Product> fetchProductAndStock(Long productId) {
        return Mono.fromCallable(() -> {
            Product product = getProductFromCacheOrDB(productId);  // Product 가져오기
            ensureStockIsAvailable(getStockFromCacheOrProduct(product));  // 재고 유효성 검사
            return product;
        });
    }
    private Product getProductFromCacheOrDB(Long productId) {
        return redisTemplate.opsForValue().get("product:" + productId) != null
                ? (Product) redisTemplate.opsForValue().get("product:" + productId)
                : productRepository.findById(productId).orElseThrow(() -> new NoSuchElementException("상품을 찾을 수 없습니다."));
    }
    private void ensureStockIsAvailable(Integer stock) {
        if (stock <= 0) {
            throw new NoSuchElementException("재고가 없습니다. 알림을 전송할 수 없습니다.");
        }
    }
    private Integer getStockFromCacheOrProduct(Product product) {
        Integer stock = (Integer) redisTemplate.opsForValue().get("productStock:" + product.getId());
        return stock != null ? stock : product.getStock();
    }

    private Mono<Boolean> notifyUsersAndHandleStock(Product product) {
        List<Long> notificationUserIds = getNotificationsFromCacheOrDB(product.getId());  // 알림 받을 유저 목록 조회
        incrementRestockRound(product);  // 재입고 회차 증가
        ProductNotificationHistory notificationHistory = createInProgressNotificationHistory(product);
        // 2. 생성된 알림 히스토리 저장
        saveNotificationHistory(notificationHistory);
        // 3. 알림 전송
        return sendNotificationToUsers(new NotificationContext(product, notificationUserIds, notificationHistory));  // 알림 전송
    }

    private void incrementRestockRound(Product product) {
        product.incrementRestockRound();
        productRepository.save(product);
        updateCache("product:" + product.getId(), product);
    }
    // 저장 메서드
    private void saveNotificationHistory(ProductNotificationHistory notificationHistory) {
        productNotificationHistoryRepository.save(notificationHistory);
    }
    private Mono<Boolean> sendNotificationToUsers(NotificationContext context) {
        sendInitialNotification(context);
        return notifyUsers(context)
                .then(markNotificationCompleted(context))
                .thenReturn(true);
    }
    // NotificationContext record to group related data for notification process
    private record NotificationContext(Product product, List<Long> userIds, ProductNotificationHistory notificationHistory) {
    }
    private void updateCache(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }
    private void sendInitialNotification(NotificationContext context) {
        sendNotification("재입고 알림 - 상품명 [" + context.product().getName() + "]");
    }
    private Mono<Void> notifyUsers(NotificationContext context) {
        return Flux.fromIterable(context.userIds())
                .concatMap(userId -> getStockAndNotifyUser(context, userId))
                .then();
    }
    private Mono<Void> markNotificationCompleted(NotificationContext context) {
        return Mono.fromRunnable(() -> {
            if (context.notificationHistory() != null) {
                context.notificationHistory().markCompleted();
                productNotificationHistoryRepository.save(context.notificationHistory());
            }
        });
    }
    private Mono<Void> getStockAndNotifyUser(NotificationContext context, Long userId) {
        return Mono.fromCallable(() -> getStockFromCache(context.product().getId()))
                .flatMap(stock -> handleStockAndNotifyUser(stock, context, userId));
    }
    private Integer getStockFromCache(Long productId) {
        return (Integer) redisTemplate.opsForValue().get("productStock:" + productId);
    }
    private Mono<Void> handleStockAndNotifyUser(Integer stock, NotificationContext context, Long userId) {
        if (stock == null || stock <= 0) {
            return handleStockDepleted(context);
        }
        return saveUserNotificationHistory(context, userId);
    }
    private Mono<Void> handleStockDepleted(NotificationContext context) {
        if (context.notificationHistory() != null) {
            context.notificationHistory().markCanceledBySoldOut();
            productNotificationHistoryRepository.save(context.notificationHistory());
        }
        return Mono.error(new IllegalArgumentException("재고가 0이 되어 알림 전송을 중단하였습니다."));
    }
    private Mono<Void> saveUserNotificationHistory(NotificationContext context, Long userId) {
        ProductUserNotificationHistory userHistory = new ProductUserNotificationHistory(context.product().getId(), context.notificationHistory().getRestockRound(), userId);
        productUserNotificationHistoryRepository.save(userHistory);
        context.notificationHistory().setLastUserId(userId);
        return Mono.empty();
    }

    private void sendNotification(String message) {
        sink.tryEmitNext(message);
        log.info("알림을 보냈습니다: " + message);
    }

    private Mono<Boolean> handleProcessError(Long productId, Throwable throwable) {
        log.error("재입고 알림 프로세스 중 오류 발생: ", throwable);
        ProductNotificationHistory lastNotificationHistory = getLastNotificationHistory();  // 마지막 알림 히스토리 가져오기
        saveNotificationHistoryError(productId, lastNotificationHistory);  // 오류 상태 저장
        return Mono.error(throwable);
    }
    private void saveNotificationHistoryError(Long productId, ProductNotificationHistory lastNotificationHistory) {
        Integer restockRound = (lastNotificationHistory != null) ? lastNotificationHistory.getRestockRound() : 1;
        Long lastUserId = (lastNotificationHistory != null && lastNotificationHistory.getLastUserId() != null)
                ? lastNotificationHistory.getLastUserId()
                : 0L;

        ProductNotificationHistory notificationHistory = new ProductNotificationHistory(
                productId,
                restockRound,
                ProductNotificationHistory.Status.CANCELED_BY_ERROR
        );

        notificationHistory.setLastUserId(lastUserId);
        productNotificationHistoryRepository.save(notificationHistory);
    }
    private ProductNotificationHistory getLastNotificationHistory() {
        return productNotificationHistoryRepository.findTopByOrderByIdDesc().orElse(null);
    }

    private boolean isLastNotificationFailed(ProductNotificationHistory history) {
        return history.getStatus() == ProductNotificationHistory.Status.CANCELED_BY_SOLD_OUT ||
                history.getStatus() == ProductNotificationHistory.Status.CANCELED_BY_ERROR;
    }

    private List<Long> getRemainingNotificationsFromCacheOrDB(Long productId, Long lastUserId) {
        String cacheKey = "productNotificationUserIds:" + productId;
        String userIdsStr = (String) redisTemplate.opsForValue().get(cacheKey);
        List<Long> userIds = (userIdsStr != null)
                ? parseUserIds(userIdsStr)
                : fetchAndCacheUserIdsFromDB(productId, cacheKey);
        return userIds.stream()
                .filter(userId -> userId > lastUserId)
                .toList();
    }
    private List<Long> parseUserIds(String userIdsStr) {
        return List.of(userIdsStr.split(",")).stream().map(Long::parseLong).toList();
    }
    private List<Long> fetchAndCacheUserIdsFromDB(Long productId, String cacheKey) {
        List<Long> userIds = productUserNotificationRepository.findByProductId(productId)
                .stream()
                .map(ProductUserNotification::getUserId)
                .toList();
        if (!userIds.isEmpty()) {
            String userIdsStr = String.join(",", userIds.stream().map(String::valueOf).toArray(String[]::new));
            updateCache(cacheKey, userIdsStr);
        }
        return userIds;
    }
    private List<Long> getNotificationsFromCacheOrDB(Long productId) {
        String cacheKey = "productNotificationUserIds:" + productId;
        String userIdsStr = (String) redisTemplate.opsForValue().get(cacheKey);
        List<Long> userIds = (userIdsStr != null)
                ? parseUserIds(userIdsStr)
                : fetchAndCacheUserIdsFromDB(productId, cacheKey);
        validateNotificationUserIdsExist(userIds);
        return userIds;
    }


    private void validateNotificationUserIdsExist(List<Long> userIds) {
        if (userIds.isEmpty()) {
            throw new NoSuchElementException("알림을 설정한 유저가 없습니다.");
        }
    }

    private Mono<Boolean> sendNotificationAndSaveHistory(Product product, List<Long> notificationUserIds) {
        incrementRestockRound(product);
        ProductNotificationHistory notificationHistory = createInProgressNotificationHistory(product);
        return sendNotificationToUsers(new NotificationContext(product, notificationUserIds, notificationHistory));
    }
    private ProductNotificationHistory createInProgressNotificationHistory(Product product) {
        // Product 정보로부터 ProductNotificationHistory 객체 생성
        ProductNotificationHistory notificationHistory = new ProductNotificationHistory(
                product.getId(),
                product.getTotalRestockRound(),
                ProductNotificationHistory.Status.IN_PROGRESS  // 상태는 IN_PROGRESS로 설정
        );
        return notificationHistory;
    }
}
