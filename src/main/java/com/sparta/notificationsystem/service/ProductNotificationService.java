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
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class ProductNotificationService {
    private final ProductRepository productRepository;
    private final ProductUserNotificationRepository productUserNotification;
    private final ProductUserNotificationHistoryRepository productUserNotificationHistoryRepository;
    private final ProductNotificationHistoryRepository productNotificationHistoryRepository;
    @Transactional
    public boolean processRestockNotification(Long productId) {
        ProductNotificationHistory notificationHistory = null;
        try {
            // 상품을 찾고 재입고 회차를 증가시킴
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new RuntimeException("Product not found"));
            product.incrementRestockRound();  // 재입고 회차 증가
            productRepository.save(product);

            // 재입고 알림 상태를 저장 (IN_PROGRESS)
            notificationHistory = new ProductNotificationHistory(productId, product.getTotalRestockRound(), ProductNotificationHistory.Status.IN_PROGRESS);
            productNotificationHistoryRepository.save(notificationHistory);

            // 재입고 알림을 설정한 유저 목록을 조회
            List<ProductUserNotification> notifications = productUserNotification.findByProductId(productId);

            // 알림을 설정한 유저들에게 메시지 전송 및 히스토리 기록
            for (ProductUserNotification notification : notifications) {
                // 알림 전송 중 재고가 0이 되었는지 확인
                if (product.getStock() <= 0) {
                    // 상태를 "CANCELED_BY_SOLD_OUT"으로 설정하고 중단
                    notificationHistory.markCanceledBySoldOut();
                    productNotificationHistoryRepository.save(notificationHistory);
                    return false;  // 중단
                }

                String message = "재입고 알림: 상품: " + product.getName() + ", 유저 ID: " + notification.getUserId();
                sendNotification(notification.getUserId(), message);

                // 히스토리 저장
                ProductUserNotificationHistory userHistory = new ProductUserNotificationHistory(productId, product.getTotalRestockRound(),notification.getUserId());
                productUserNotificationHistoryRepository.save(userHistory);
            }
            // 알림 전송 완료 후 상태를 "COMPLETED"로 설정
            notificationHistory.markCompleted();
            productNotificationHistoryRepository.save(notificationHistory);
            return true;
        } catch (Exception e) {
            // 오류 발생 시 기존 알림 히스토리 상태를 "CANCELED_BY_ERROR"로 변경
            if (notificationHistory != null) {
                notificationHistory.markCanceledByError();
                productNotificationHistoryRepository.save(notificationHistory);
            }
            return false;
        }
    }

    private void sendNotification(Long userId, String message) {
        // 동기적으로 알림 전송 처리
        System.out.println("유저 ID " + userId + "에게 알림: " + message);
    }
}
