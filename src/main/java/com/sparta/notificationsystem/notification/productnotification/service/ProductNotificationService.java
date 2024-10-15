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

    // [Product와 Stock의 상태를 확인하는 프로세스]
    // redis에 있으면 redis에서, 없으면 DB에서 가져온다.
    private Mono<Product> fetchProductAndStock(Long productId) {
        return Mono.fromCallable(() -> {
            Product product = getProductFromCacheOrDB(productId);  // Product 가져오기
            ensureStockIsAvailable(getStockFromCacheOrProduct(product));  // 재고 유효성 검사
            return product;
        });
    }

    // 1. Product 확인해서 cache 혹은 DB에서 가져오고 없으면 에러
    private Product getProductFromCacheOrDB(Long productId) {
        return redisTemplate.opsForValue().get("product:" + productId) != null
                ? (Product) redisTemplate.opsForValue().get("product:" + productId)
                : productRepository.findById(productId).orElseThrow(() -> new NoSuchElementException("상품을 찾을 수 없습니다."));
    }

    // 2. 재고 없으면 에러
    private void ensureStockIsAvailable(Integer stock) {
        if (stock <= 0) {
            throw new NoSuchElementException("재고가 없습니다. 알림을 전송할 수 없습니다.");
        }
    }

    // 3. redis에서 저장된 재고 수량을 가져옴
    private Integer getStockFromCacheOrProduct(Product product) {
        Integer stock = (Integer) redisTemplate.opsForValue().get("productStock:" + product.getId());

        if (stock == null) {
            // 캐시에 재고가 없으면 DB에서 조회하고 Redis에 저장
            stock = product.getStock();
            redisTemplate.opsForValue().set("productStock:" + product.getId(), stock);
        }

        return stock;
    }


    // [상품 재입고 알림 전송, 회차 증가, 저장 프로세스]
    private Mono<Boolean> notifyUsersAndHandleStock(Product product) {
        List<Long> notificationUserIds = getNotificationsFromCacheOrDB(product.getId());  // 알림 받을 유저 목록 조회
        incrementRestockRound(product);  // 재입고 회차 증가
        ProductNotificationHistory notificationHistory = createInProgressNotificationHistory(product);
        // 2. 생성된 알림 히스토리 저장
        saveNotificationHistory(notificationHistory);
        // 3. 알림 전송
        return sendNotificationToUsers(new NotificationContext(product, notificationUserIds, notificationHistory));  // 알림 전송
    }
    // 1. 알림 받을 유저 목록 조회
    private List<Long> getNotificationsFromCacheOrDB(Long productId) {
        String cacheKey = "productNotificationUserIds:" + productId;
        String userIdsStr = (String) redisTemplate.opsForValue().get(cacheKey);
        List<Long> userIds = (userIdsStr != null)
                ? parseUserIds(userIdsStr)
                : fetchAndCacheUserIdsFromDB(productId, cacheKey);
        validateNotificationUserIdsExist(userIds);
        return userIds;
    }

    // 2. 재입고 회차를 증가시키는 메서드
    private void incrementRestockRound(Product product) {
        product.incrementRestockRound();    // 1회 증가
        productRepository.save(product);
        updateCache("product:" + product.getId(), product); // Redis에 업데이트
    }

    // 3. productNotificationHistoryRepository에 저장 메서드
    private void saveNotificationHistory(ProductNotificationHistory notificationHistory) {
        productNotificationHistoryRepository.save(notificationHistory);
    }

    // [알림 전송 프로세스]
    private Mono<Boolean> sendNotificationToUsers(NotificationContext context) {
        sendInitialNotification(context);
        return notifyUsers(context)
                .last()  // 마지막 유저 처리 후
                .flatMap(lastUserId -> markNotificationCompleted(context, lastUserId))  // 마지막 유저 ID 전달
                .thenReturn(true);
    }
    // 1. 알림 보내는 문장
    private void sendInitialNotification(NotificationContext context) {
        sendNotification("재입고 알림 - 상품명 [" + context.product().getName() + "]");
    }

    // 2. 알림 보내는 메서드
    private void sendNotification(String message) {
        sink.tryEmitNext(message);
        log.info("알림을 보냈습니다: " + message);
    }

    // 3. 유저에게 개별 알림 처리
    private Flux<Long> notifyUsers(NotificationContext context) {
        return Flux.fromIterable(context.userIds())
                .concatMap(userId -> getStockAndNotifyUser(context, userId)
                        .flatMap(unused -> saveUserNotificationHistory(context, userId))  // JPA 저장이 완료될 때까지 기다림
                        .thenReturn(userId)  // 유저 ID 반환
                );
    }


    // 4. 알림 완료 상태 갱신 및 저장
    private Mono<Void> markNotificationCompleted(NotificationContext context, Long lastUserId) {
        return Mono.fromRunnable(() -> {
            if (context.notificationHistory() != null) {
                context.notificationHistory().setLastUserId(lastUserId);
                context.notificationHistory().markCompleted();
                productNotificationHistoryRepository.save(context.notificationHistory());
            }
        });
    }

    // Redis에 Cache 저장하는 메서드
    private void updateCache(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    // 상품의 재고 상태를 확인하고 재고가 있을 경우 알림 전송
    private Mono<Void> getStockAndNotifyUser(NotificationContext context, Long userId) {
        return Mono.fromCallable(() -> getStockFromCache(context.product().getId()))
                .flatMap(stock -> handleStockAndNotifyUser(stock, context, userId));
    }

    // Redis에서 재고를 확인한다.
    private Integer getStockFromCache(Long productId) {
        return (Integer) redisTemplate.opsForValue().get("productStock:" + productId);
    }

    // 재고를 처리하고 알림을 전송한다.
    private Mono<Void> handleStockAndNotifyUser(Integer stock, NotificationContext context, Long userId) {
        if (stock == null || stock <= 0) {
            return handleStockDepleted(context);  // 재고가 0이거나 없으면 품절 처리
        }
        // 재고가 있을 때
        return saveUserNotificationHistory(context, userId);  // 재고가 있을 때 알림 히스토리 저장
    }


    // 재고가 없는 경우 품절 처리를 하고 저장한 뒤 에러를 던진다.
    private Mono<Void> handleStockDepleted(NotificationContext context) {
        if (context.notificationHistory() != null) {
            context.notificationHistory().markCanceledBySoldOut();
            productNotificationHistoryRepository.save(context.notificationHistory());
        }
        return Mono.error(new IllegalArgumentException("재고가 0이 되어 알림 전송을 중단하였습니다."));
    }

    // 유저별 알림 히스토리를 저장한다. 마지막 알림 유저를 업데이트 한다.
    private Mono<Void> saveUserNotificationHistory(NotificationContext context, Long userId) {
        ProductUserNotificationHistory userHistory = new ProductUserNotificationHistory(context.product().getId(), context.notificationHistory().getRestockRound(), userId);
        productUserNotificationHistoryRepository.save(userHistory);
        context.notificationHistory().setLastUserId(userId);
        return Mono.empty();
    }

    // 마지막 알림 히스토리를 가져온다.
    private ProductNotificationHistory getLastNotificationHistory() {
        return productNotificationHistoryRepository.findTopByOrderByIdDesc().orElse(null);
    }

    // [에러 처리]
    private Mono<Boolean> handleProcessError(Long productId, Throwable throwable) {
        log.error("재입고 알림 프로세스 중 오류 발생: ", throwable);
        ProductNotificationHistory lastNotificationHistory = getLastNotificationHistory();  // 마지막 알림 히스토리 가져오기
        saveNotificationHistoryError(productId, lastNotificationHistory);  // 오류 상태 저장
        return Mono.error(throwable);
    }

    // 1. 오류의 상태 저장 (마지막 성공 유저 아이디도 함께 저장)
    private void saveNotificationHistoryError(Long productId, ProductNotificationHistory lastNotificationHistory) {
        Integer restockRound = (lastNotificationHistory != null) ? lastNotificationHistory.getRestockRound() : 1;
        Long lastUserId = (lastNotificationHistory != null && lastNotificationHistory.getLastUserId() != null)
                ? lastNotificationHistory.getLastUserId()
                : 0L;   // 마지막 유저 아이디가 없으면 0 저장

        ProductNotificationHistory notificationHistory = new ProductNotificationHistory(
                productId,
                restockRound,
                lastUserId,
                ProductNotificationHistory.Status.CANCELED_BY_ERROR
        );

        notificationHistory.setLastUserId(lastUserId);
        productNotificationHistoryRepository.save(notificationHistory);
    }

    // 이전의 마지막 알림이 품절이나 에러로 중단되었는지 확인
    private boolean isLastNotificationFailed(ProductNotificationHistory history) {
        return history.getStatus() == ProductNotificationHistory.Status.CANCELED_BY_SOLD_OUT ||
                history.getStatus() == ProductNotificationHistory.Status.CANCELED_BY_ERROR;
    }

    // 남은 알림을 Redis 혹은 DB에서 가져옴
    // 이때 Redis에 id값들을 String으로 넣어놨기 때문에 파싱해서 가져옴
    private List<Long> getRemainingNotificationsFromCacheOrDB(Long productId, Long lastUserId) {
        String cacheKey = "productNotificationUserIds:" + productId;
        String userIdsStr = (String) redisTemplate.opsForValue().get(cacheKey);
        List<Long> userIds = (userIdsStr != null)
                ? parseUserIds(userIdsStr)  // 유저 목록이 있으면 파싱
                : fetchAndCacheUserIdsFromDB(productId, cacheKey);  // 없으면 DB에서 가져옴
        return userIds.stream()
                .filter(userId -> userId > lastUserId)  // lastUserId 보다 큰 유저만 가져옴
                .toList();
    }

    // String으로 저장된 유저 아이디들을 List<Long>으로 변환한다.
    private List<Long> parseUserIds(String userIdsStr) {
        return List.of(userIdsStr.split(",")).stream().map(Long::parseLong).toList();
    }

    //  DB에서 유저 id 목록을 조회한 뒤 Redis에 저장한다.
    private List<Long> fetchAndCacheUserIdsFromDB(Long productId, String cacheKey) {
        List<Long> userIds = productUserNotificationRepository.findByProductId(productId)
                .stream()
                .map(ProductUserNotification::getUserId)
                .toList();
        if (!userIds.isEmpty()) {
            // String으로 변환해서 Redis에 저장한다.
            String userIdsStr = String.join(",", userIds.stream().map(String::valueOf).toArray(String[]::new));
            updateCache(cacheKey, userIdsStr);
        }
        return userIds;
    }

    // 알림 설정 유저가 없는 경우 에러를 던진다.
    private void validateNotificationUserIdsExist(List<Long> userIds) {
        if (userIds.isEmpty()) {
            throw new NoSuchElementException("알림을 설정한 유저가 없습니다.");
        }
    }

    // 알림을 보내고 ProductNotificationHistory를 저장한다.
    private Mono<Boolean> sendNotificationAndSaveHistory(Product product, List<Long> notificationUserIds) {
        incrementRestockRound(product);
        ProductNotificationHistory notificationHistory = createInProgressNotificationHistory(product);
        return sendNotificationToUsers(new NotificationContext(product, notificationUserIds, notificationHistory));
    }

    // 알림 과정 중 상태를 IN_PROGRESS 설정한다.
    private ProductNotificationHistory createInProgressNotificationHistory(Product product) {
        // Product 정보로부터 ProductNotificationHistory 객체 생성
        ProductNotificationHistory notificationHistory = new ProductNotificationHistory(
                product.getId(),
                product.getTotalRestockRound(),
                ProductNotificationHistory.Status.IN_PROGRESS  // 상태는 IN_PROGRESS로 설정
        );
        return notificationHistory;
    }

    // 알림 과정에 필요한 Context 간단하게 저장
    private record NotificationContext(Product product, List<Long> userIds, ProductNotificationHistory notificationHistory) {
    }
}
