package com.sparta.notificationsystem.notification.productnotification.repository;

import com.sparta.notificationsystem.notification.productusernotification.entity.ProductUserNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductUserNotificationRepository extends JpaRepository<ProductUserNotification, Long> {
    List<ProductUserNotification> findByProductId(Long productId);

    List<ProductUserNotification> findByProductIdAndUserIdGreaterThan(Long productId, Long lastUserId);
}
