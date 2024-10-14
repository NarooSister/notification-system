package com.sparta.notificationsystem.service;

import com.sparta.notificationsystem.entity.Product;
import com.sparta.notificationsystem.entity.ProductNotificationHistory;
import com.sparta.notificationsystem.entity.ProductUserNotification;
import com.sparta.notificationsystem.entity.ProductUserNotificationHistory;
import com.sparta.notificationsystem.repository.ProductNotificationHistoryRepository;
import com.sparta.notificationsystem.repository.ProductRepository;
import com.sparta.notificationsystem.repository.ProductUserNotificationHistoryRepository;
import com.sparta.notificationsystem.repository.ProductUserNotificationRepository;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
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
@AllArgsConstructor
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

                    // Redis 또는 DB에서 알림 유저 목록 가져오기
                    List<ProductUserNotification> notifications = getNotificationsFromCacheOrDB(productId);
                    // 재입고 회차 증가
                    incrementRestockRound(product);
                    // 재입고 알림 History 저장
                    ProductNotificationHistory notificationHistory = saveNotificationHistory(product);
                    // 유저에게 알림 보내기
                    sendNotificationToUsers(productId, notifications, product, notificationHistory, stock);
                    return true;    // 재입고 알림 프로세스 성공
                })

                // JPA의 블로킹 작업을 Scheduler를 사용하여 별도의 스레드에서 처리
                .subscribeOn(Schedulers.boundedElastic());  // 블로킹 작업을 위한 스레드 풀에서 실행
    }

    private Product getProductFromCacheOrDB(Long productId) {
        Product product = (Product) redisTemplate.opsForValue().get("product:" + productId);
        if (product == null) {
            log.info("Product 캐시 미스:" + productId);
            product = productRepository.findById(productId)
                    .orElseThrow(() -> new NoSuchElementException("상품을 찾을 수 없습니다."));
            redisTemplate.opsForValue().set("product:" + productId, product);
        } else {
            log.info("Product 캐시 히트:" + productId);
        }
        return product;
    }

    private Integer getStockFromCacheOrProduct(Product product) {
        // 재고 상태 확인 (Redis에서 먼저 조회)
        Integer stock = (Integer) redisTemplate.opsForValue().get("productStock:" + product.getId());
        if (stock == null) {
            stock = product.getStock();
            redisTemplate.opsForValue().set("productStock:" + product.getId(), stock);
        }
        return stock;
    }

    private void validateStockExist(Integer stock) {
        // 재고가 없는 경우 알림 전송 중단
        if (stock <= 0) {
            throw new NoSuchElementException("재고가 없습니다. 알림을 전송할 수 없습니다.");
        }
    }

    private List<ProductUserNotification> getNotificationsFromCacheOrDB(Long productId) {
        // Redis에서 ProductUserNotification 캐시 조회
        List<ProductUserNotification> notifications = (List<ProductUserNotification>) redisTemplate.opsForValue().get("productNotifications:" + productId);
        if (notifications == null) {
            // 캐시에 없으면 DB에서 조회 후 캐시에 저장
            notifications = productUserNotificationRepository.findByProductId(productId);
            redisTemplate.opsForValue().set("productNotifications:" + productId, notifications);
        }
        // 예외 발생 처리 메서드 호출
        validateNotificationsExist(notifications);
        return notifications;
    }

    // 알림 설정 유저가 없는 경우에 예외 발생
    private void validateNotificationsExist(List<ProductUserNotification> notifications) {
        if (notifications.isEmpty()) {
            throw new NoSuchElementException("알림을 설정한 유저가 없습니다.");
        }
    }

    private void incrementRestockRound(Product product) {
        // 상품을 찾고 재입고 회차를 증가시킴
        product.incrementRestockRound();
        productRepository.save(product);
        // Redis 캐시를 갱신 (Product 정보 업데이트)
        redisTemplate.opsForValue().set("product:" + product.getId(), product);
    }

    private ProductNotificationHistory saveNotificationHistory(Product product) {
        // 재입고 알림 상태를 저장 (IN_PROGRESS)
        ProductNotificationHistory notificationHistory = new ProductNotificationHistory(product.getId(), product.getTotalRestockRound(), ProductNotificationHistory.Status.IN_PROGRESS);
        productNotificationHistoryRepository.save(notificationHistory);
        return notificationHistory;
    }

    private void sendNotificationToUsers(Long productId, List<ProductUserNotification> notifications, Product product, ProductNotificationHistory notificationHistory, Integer stock) {
        String message = "재입고 알림 - 상품명 [" + product.getName() + "]";
        sendNotification(message);

        // 유저들에게 메시지 전송 및 히스토리 기록
        for (ProductUserNotification notification : notifications) {
            stock = (Integer) redisTemplate.opsForValue().get("productStock:" + productId);
            if (stock <= 0) {
                // 상태를 "CANCELED_BY_SOLD_OUT"으로 설정하고 중단
                notificationHistory.markCanceledBySoldOut();
                productNotificationHistoryRepository.save(notificationHistory);
                throw new IllegalArgumentException("재고가 0이 되어 알림 전송을 중단하였습니다.");
            }

            // 히스토리 저장 (JPA 블로킹 작업)
            ProductUserNotificationHistory userHistory = new ProductUserNotificationHistory(productId, product.getTotalRestockRound(), notification.getUserId());
            productUserNotificationHistoryRepository.save(userHistory);
        }

        // 알림 전송 완료 후 상태를 "COMPLETED"로 설정
        notificationHistory.markCompleted();
        productNotificationHistoryRepository.save(notificationHistory);
    }

    // 알림을 전송하는 메서드
    public void sendNotification(String message) {
        // SSE를 통해 알림을 비동기적으로 전송
        sink.tryEmitNext(message);
        log.info("알림을 보냈습니다: " + message);
    }

    // SSE 스트림을 제공하는 메서드
    public Flux<String> getNotificationStream() {
        // Sink에서 Flux로 변환하여 알림을 스트리밍
        return restockNotificationStream;
    }
}
