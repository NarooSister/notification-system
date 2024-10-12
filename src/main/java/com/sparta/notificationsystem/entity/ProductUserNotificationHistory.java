package com.sparta.notificationsystem.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductUserNotificationHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long productId; // 알림 발송 상품
    private Integer restockRound;   // 이번 알림의 재입고 회차
    private Long userId;    // 알림을 받은 유저

    public ProductUserNotificationHistory(Long productId, Integer restockRound, Long userId) {
        this.productId = productId;
        this.restockRound = restockRound;
        this.userId = userId;
    }
}
