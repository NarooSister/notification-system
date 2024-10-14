package com.sparta.notificationsystem.notification.productusernotification.repository;

import com.sparta.notificationsystem.notification.productusernotification.entity.ProductUserNotificationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductUserNotificationHistoryRepository extends JpaRepository<ProductUserNotificationHistory, Long> {
}
