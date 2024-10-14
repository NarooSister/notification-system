package com.sparta.notificationsystem;

import com.sparta.notificationsystem.entity.ProductUserNotification;
import com.sparta.notificationsystem.repository.ProductUserNotificationHistoryRepository;
import com.sparta.notificationsystem.repository.ProductUserNotificationRepository;
import com.sparta.notificationsystem.service.ProductNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class ProductNotificationServiceTest {

    @Autowired
    private ProductNotificationService productNotificationService;

    @Autowired
    private ProductUserNotificationRepository productUserNotificationRepository;

    @Test
    @Transactional
    public void testSendNotificationsTo500Users() {
        Long productId = 1L;

        // 500명의 유저가 재입고 알림을 설정했다고 가정하고 알림 설정
        IntStream.range(0, 500).forEach(i -> {
            ProductUserNotification userNotification = new ProductUserNotification(productId, (long) i);
            productUserNotificationRepository.save(userNotification);
        });

        // 실제 알림을 전송 (Mono로 비동기 처리)
        Mono<Boolean> result = productNotificationService.processRestockNotification(productId)
                .subscribeOn(Schedulers.boundedElastic());

        // 비동기 작업 대기
        Boolean success = result.block(); // 비동기 작업을 기다림

        // 알림 전송이 성공했는지 확인
        assertThat(success).isTrue();
    }
    @Test
    @DisplayName("스케줄러에 의해 비동기 작업이 되었는지 스레드 확인")
    public void testThreadExecution() {
        Long productId = 1L;

        Mono.fromCallable(() -> {
                    System.out.println("테스트 스레드 이름: " + Thread.currentThread().getName());
                    return productNotificationService.processRestockNotification(productId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .block();  // 비동기 작업을 동기적으로 처리하여 테스트
    }

}
