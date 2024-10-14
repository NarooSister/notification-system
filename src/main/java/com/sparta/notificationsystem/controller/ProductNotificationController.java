package com.sparta.notificationsystem.controller;

import com.sparta.notificationsystem.service.ProductNotificationService;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@AllArgsConstructor
public class ProductNotificationController {
    private final ProductNotificationService productNotificationService;
    @PostMapping("/products/{productId}/notifications/re-stock")
    public Mono<ResponseEntity<String>> postNotifications(@PathVariable("productId") Long productId) {
        return productNotificationService.processRestockNotification(productId)
                .subscribeOn(Schedulers.boundedElastic())  // JPA 블로킹 작업을 비동기적으로 처리
                .map(success -> ResponseEntity.ok("재입고 알림이 성공적으로 전송되었습니다."));
    }
    @PostMapping("/admin/products/{productId}/notifications/re-stock")
    public void getNotificationManual(@PathVariable("productId") Long productId){

    }

    @GetMapping(value = "/products/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamNotifications() {
        return productNotificationService.getNotificationStream()
                .map(message -> ServerSentEvent.<String>builder()
                        .event("restock-notification")  // 이벤트 타입 설정
                        .data(message)  // 전송할 메시지 데이터
                        .build());
    }
}

