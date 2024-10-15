package com.sparta.notificationsystem.notification.productnotification.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductNotificationHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long productId;
    private Integer restockRound;   // 이번 알림의 재입고 회차
    private Long lastUserId;    // 마지막 발송 유저 아이디 저장
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

    public ProductNotificationHistory(Long productId, Integer restockRound,  Long lastUserId, Status status) {
        this.productId = productId;
        this.restockRound = restockRound;
        this.lastUserId = lastUserId;
        this.status = status;
    }

    public ProductNotificationHistory(Long productId, Integer restockRound, Status status) {
        this.productId = productId;
        this.restockRound = restockRound;
        this.status = status;
    }

    public void setLastUserId(Long lastUserId) {
        this.lastUserId = lastUserId;
    }

    // status 상태를 변경하는 메서드들
    public void markInProgress() {
        this.status = Status.IN_PROGRESS;
    }

    public void markCanceledBySoldOut() {
        this.status = Status.CANCELED_BY_SOLD_OUT;
    }

    public void markCanceledByError() {
        this.status = Status.CANCELED_BY_ERROR;
    }

    public void markCompleted() {
        this.status = Status.COMPLETED;
    }
}
