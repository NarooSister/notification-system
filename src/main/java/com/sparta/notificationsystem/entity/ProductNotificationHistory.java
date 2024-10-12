package com.sparta.notificationsystem.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProductNotificationHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long productId;
    private Integer restockRound;   // 이번 알림의 재입고 회차

    @Enumerated(EnumType.STRING)
    private Status status;

    public enum Status {
        IN_PROGRESS("발송 중"),
        CANCELED_BY_SOLD_OUT("품절에 의한 발송 중단"),
        CANCELED_BY_ERROR("예외에 의한 발송 중단"),
        COMPLETED("완료");

        private final String description;

        Status(String description){
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
