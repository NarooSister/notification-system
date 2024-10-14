package com.sparta.notificationsystem.notification.productusernotification.repository;

import com.sparta.notificationsystem.notification.productnotification.entity.ProductNotificationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ProductNotificationHistoryRepository extends JpaRepository<ProductNotificationHistory, Long> {

    Optional<ProductNotificationHistory> findTopByOrderByIdDesc();
}
