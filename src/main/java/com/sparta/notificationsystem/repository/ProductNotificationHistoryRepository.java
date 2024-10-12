package com.sparta.notificationsystem.repository;

import com.sparta.notificationsystem.entity.ProductNotificationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductNotificationHistoryRepository extends JpaRepository<ProductNotificationHistory, Long> {
}
