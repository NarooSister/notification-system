package com.sparta.notificationsystem.repository;

import com.sparta.notificationsystem.entity.ProductUserNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductUserNotificationRepository extends JpaRepository<ProductUserNotification, Long> {
    List<ProductUserNotification> findByProductId(Long productId);
}
