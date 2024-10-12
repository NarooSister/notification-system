package com.sparta.notificationsystem.dto;

import com.sparta.notificationsystem.entity.ProductUserNotificationHistory;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductUserNotificationHistoryDto {
    private Long productId;
    private Integer restockRound;
    private Long userId;

    public static ProductUserNotificationHistoryDto fromEntity(ProductUserNotificationHistory entity) {
        return ProductUserNotificationHistoryDto.builder()
                .productId(entity.getProductId())
                .restockRound(entity.getRestockRound())
                .userId(entity.getUserId())
                .build();
    }

    public ProductUserNotificationHistory toEntity() {
        return ProductUserNotificationHistory.builder()
                .productId(this.productId)
                .restockRound(this.restockRound)
                .userId(this.userId)
                .build();
    }
}
