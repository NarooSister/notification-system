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
        return Mono.fromCallable(() -> {
                    // Redis 또는 DB에서 Product, stock 상태 확인
                    Product product = getProductFromCacheOrDB(productId);
                    Integer stock = getStockFromCacheOrProduct(product);

                    // 재고가 없는 경우 알림 중단하고 예외
                    validateStockExist(stock);

                    // 최근 알림 상태 확인 후 결정
                    ProductNotificationHistory lastNotificationHistory = productNotificationHistoryRepository.findTopByOrderByIdDesc()
                            .orElse(null);

                    // 알림 설정한 유저 목록
                    List<Long> notificationUserIds;

                    if (lastNotificationHistory != null && (lastNotificationHistory.getStatus() == ProductNotificationHistory.Status.CANCELED_BY_SOLD_OUT ||
                            lastNotificationHistory.getStatus() == ProductNotificationHistory.Status.CANCELED_BY_ERROR)) {
                        // 이전 알림이 품절 또는 오류로 중단되었으면 마지막 유저 이후의 목록 가져오기
                        notificationUserIds = getRemainingNotificationsFromCacheOrDB(productId, lastNotificationHistory.getLastUserId());
                    } else {
                        // 새로 모든 유저에게 알림 보내기
                        notificationUserIds = getNotificationsFromCacheOrDB(productId);
                        // 재입고 회차 증가
                        incrementRestockRound(product);
                    }

                    // 재입고 알림 History 저장
                    ProductNotificationHistory notificationHistory = saveNotificationHistory(product);

                    // 유저에게 알림 보내기
                    return new NotificationContext(product, notificationUserIds, notificationHistory);

                })
                .flatMap(context -> sendNotificationToUsers(context))
                // JPA의 블로킹 작업을 Scheduler를 사용하여 별도의 스레드에서 처리
                .subscribeOn(Schedulers.boundedElastic()) // 블로킹 작업을 위한 스레드 풀에서 실행
                .onErrorResume(throwable -> {
                    // 전체 프로세스에서 발생하는 예외 처리
                    log.error("재입고 알림 프로세스 중 오류 발생: ", throwable);
                    ProductNotificationHistory lastNotificationHistory = productNotificationHistoryRepository.findTopByOrderByIdDesc().orElse(null);
                    ProductNotificationHistory notificationHistory = new ProductNotificationHistory(
                            productId,
                            lastNotificationHistory != null ? lastNotificationHistory.getRestockRound() : 1,
                            ProductNotificationHistory.Status.CANCELED_BY_ERROR);
                    notificationHistory.setLastUserId(lastNotificationHistory != null ? lastNotificationHistory.getLastUserId() : 0L);
                    productNotificationHistoryRepository.save(notificationHistory);
                    return Mono.error(throwable);
                });
    }

    private List<Long> getRemainingNotificationsFromCacheOrDB(Long productId, Long lastUserId) {
        // Redis에서 ProductUserNotification 캐시 조회 후 마지막 유저 이후의 목록 필터링
        String userIdsStr = (String) redisTemplate.opsForValue().get("productNotificationUserIds:" + productId);
        List<Long> userIds;
        if (userIdsStr == null) {
            // 캐시에 없으면 DB에서 조회 후 캐시에 저장
            userIds = productUserNotificationRepository.findByProductIdAndUserIdGreaterThan(productId, lastUserId)
                    .stream().map(ProductUserNotification::getUserId).toList();
            updateCache("productNotificationUserIds:" + productId, String.join(",", userIds.stream().map(String::valueOf).toList()));
        } else {
            userIds = Arrays.stream(userIdsStr.split(","))
                    .map(Long::parseLong)
                    .filter(userId -> userId > lastUserId)
                    .toList();
        }
        // 예외 발생 처리 메서드 호출
        validateNotificationUserIdsExist(userIds);
        return userIds;
    }

    private Product getProductFromCacheOrDB(Long productId) {
        Product product = (Product) redisTemplate.opsForValue().get("product:" + productId);
        if (product == null) {
            product = productRepository.findById(productId)
                    .orElseThrow(() -> new NoSuchElementException("상품을 찾을 수 없습니다."));
            updateCache("product:" + productId, product);
        }
        return product;
    }

    private Integer getStockFromCacheOrProduct(Product product) {
        // 재고 상태 확인 (Redis에서 먼저 조회)
        Integer stock = (Integer) redisTemplate.opsForValue().get("productStock:" + product.getId());
        if (stock == null) {
            stock = product.getStock();
            updateCache("productStock:" + product.getId(), stock);
        }
        return stock;
    }

    private void validateStockExist(Integer stock) {
        // 재고가 없는 경우 알림 전송 중단
        if (stock <= 0) {
            throw new NoSuchElementException("재고가 없습니다. 알림을 전송할 수 없습니다.");
        }
    }

    private List<Long> getNotificationsFromCacheOrDB(Long productId) {
        // Redis에서 ProductUserNotification 캐시 조회
        String userIdsStr = (String) redisTemplate.opsForValue().get("productNotificationUserIds:" + productId);
        List<Long> userIds;
        if (userIdsStr == null) {
            // 캐시에 없으면 DB에서 조회 후 캐시에 저장
            userIds = productUserNotificationRepository.findByProductId(productId)
                    .stream().map(ProductUserNotification::getUserId).toList();
            updateCache("productNotificationUserIds:" + productId, String.join(",", userIds.stream().map(String::valueOf).toList()));
        }  else {
            userIds = Arrays.stream(userIdsStr.split(",")).map(Long::parseLong).toList();
        }
        // 예외 발생 처리 메서드 호출
        validateNotificationUserIdsExist(userIds);
        return userIds;
    }
    // 알림 설정 유저가 없는 경우에 예외 발생
    private void validateNotificationUserIdsExist(List<Long> userIds) {
        if (userIds.isEmpty()) {
            throw new NoSuchElementException("알림을 설정한 유저가 없습니다.");
        }
    }

    private void incrementRestockRound(Product product) {
        // 상품을 찾고 재입고 회차를 증가시킴
        product.incrementRestockRound();
        productRepository.save(product);
        // Redis 캐시를 갱신 (Product 정보 업데이트)
        updateCache("product:" + product.getId(), product);
    }

    private ProductNotificationHistory saveNotificationHistory(Product product) {
        // 재입고 알림 상태를 저장 (IN_PROGRESS)
        ProductNotificationHistory notificationHistory = new ProductNotificationHistory(product.getId(),
                product.getTotalRestockRound(), ProductNotificationHistory.Status.IN_PROGRESS);
        productNotificationHistoryRepository.save(notificationHistory);
        return notificationHistory;
    }

    private Mono<Boolean> sendNotificationToUsers(NotificationContext context) {
        sendInitialNotification(context);
        return notifyUsers(context)
                .then(markNotificationCompleted(context))
                .thenReturn(true);
    }

    private void sendInitialNotification(NotificationContext context) {
        String message = "재입고 알림 - 상품명 [" + context.product().getName() + "]";
        sendNotification(message);
    }

    private Flux<Void> notifyUsers(NotificationContext context) {
        return Flux.fromIterable(context.userIds())
                .concatMap(userId -> Mono.fromCallable(() -> (Integer) redisTemplate.opsForValue().get("productStock:" + context.product().getId()))
                        .flatMap(currentStock -> handleStockAndNotifyUser(currentStock, context, userId)));
    }

    private Mono<Void> handleStockAndNotifyUser(Integer currentStock, NotificationContext context, Long userId) {
        if (currentStock == null || currentStock <= 0) {
            // 재고가 0이면 예외 발생, 전체 알림 프로세스 종료
            if (context.notificationHistory() != null) {
                context.notificationHistory().markCanceledBySoldOut();
                productNotificationHistoryRepository.save(context.notificationHistory());
            }
            return Mono.error(new IllegalArgumentException("재고가 0이 되어 알림 전송을 중단하였습니다."));
        }
        // 유저별 알림 히스토리 저장
        ProductUserNotificationHistory userHistory = new ProductUserNotificationHistory(context.product().getId(), context.notificationHistory().getRestockRound(), userId);
        productUserNotificationHistoryRepository.save(userHistory);
        context.notificationHistory().setLastUserId(userId);
        productUserNotificationHistoryRepository.save(userHistory);
        return Mono.empty();
    }

    private Mono<Void> markNotificationCompleted(NotificationContext context) {
        return Mono.fromRunnable(() -> {
            // 알림 전송 완료 후 상태를 "COMPLETED"로 설정
            if (context.notificationHistory() != null) {
                context.notificationHistory().markCompleted();
                productNotificationHistoryRepository.save(context.notificationHistory());
            }
        });
    }

    private void updateCache(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    // 알림을 전송하는 메서드
    public void sendNotification(String message) {
        // SSE를 통해 알림을 비동기적으로 전송
        sink.tryEmitNext(message);
        log.info("알림을 보냈습니다: " + message);
    }

    private record NotificationContext(Product product, List<Long> userIds, ProductNotificationHistory notificationHistory) {}
}
