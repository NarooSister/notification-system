package com.sparta.notificationsystem.notification.productnotification.controller;

import com.sparta.notificationsystem.notification.productnotification.service.ProductNotificationService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@Slf4j
@RequiredArgsConstructor
public class ProductNotificationController {
    private final ProductNotificationService productNotificationService;
    @PostMapping("/products/{productId}/notifications/re-stock")
    @RateLimiter(name = "default")
    public Mono<ResponseEntity<String>> postNotifications(@PathVariable("productId") Long productId) {
        return productNotificationService.processRestockNotification(productId)
                .subscribeOn(Schedulers.boundedElastic())  // JPA 블로킹 작업을 비동기적으로 처리
                .map(success -> ResponseEntity.ok("재입고 알림이 성공적으로 전송되었습니다."));
    }

    @PostMapping("/admin/products/{productId}/notifications/re-stock")
    @RateLimiter(name = "default")
    public Mono<ResponseEntity<String>> getNotificationManual(@PathVariable("productId") Long productId) {
        return productNotificationService.processRestockNotificationManual(productId)
                .subscribeOn(Schedulers.boundedElastic())  // JPA 블로킹 작업을 비동기적으로 처리
                .map(success -> ResponseEntity.ok("재입고 알림이 성공적으로 전송되었습니다."));
    }
}

