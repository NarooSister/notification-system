package com.sparta.notificationsystem.repository;

import com.sparta.notificationsystem.entity.ProductUserNotificationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductUserNotificationHistoryRepository extends JpaRepository<ProductUserNotificationHistory, Long> {
}
