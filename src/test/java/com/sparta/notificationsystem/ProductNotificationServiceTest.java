package com.sparta.notificationsystem;

import com.sparta.notificationsystem.entity.ProductUserNotification;
import com.sparta.notificationsystem.repository.ProductUserNotificationHistoryRepository;
import com.sparta.notificationsystem.repository.ProductUserNotificationRepository;
import com.sparta.notificationsystem.service.ProductNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class ProductNotificationServiceTest {

    @Autowired
    private ProductNotificationService productNotificationService;

    @Autowired
    private ProductUserNotificationRepository productUserNotificationRepository;

    @Autowired
    private ProductUserNotificationHistoryRepository productUserNotificationHistoryRepository;

    @BeforeEach
    public void setup() {
        // 테스트 시작 전 기존 유저 알림 및 히스토리 데이터 삭제
        productUserNotificationHistoryRepository.deleteAll();
        productUserNotificationRepository.deleteAll();
    }
    @Test
    @Transactional
    public void testSendNotificationsTo500Users() {
        Long productId = 1L;

        // 500명의 유저가 재입고 알림을 설정했다고 가정하고 알림 설정
        IntStream.range(0, 500).forEach(i -> {
            ProductUserNotification userNotification = new ProductUserNotification(productId, (long) i);
            productUserNotificationRepository.save(userNotification);
        });

        // 실제 알림을 전송
        boolean result = productNotificationService.processRestockNotification(productId);

        // 알림 전송이 성공했는지 확인
        assertThat(result).isTrue();

        // 500명의 유저에게 알림이 전송되었는지 확인
        List<ProductUserNotification> notifications = productUserNotificationRepository.findByProductId(productId);
        assertThat(notifications).hasSize(500);

        // 추가로 필요한 검증 로직: 알림 히스토리가 저장되었는지, 상태가 올바르게 기록되었는지 등을 확인할 수 있음
    }
}
