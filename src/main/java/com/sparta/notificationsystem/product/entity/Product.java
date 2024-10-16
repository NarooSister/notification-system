package com.sparta.notificationsystem.product.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Integer totalRestockRound;  // 총 재입고 회차 수
    private String name;    // 상품 이름
    private Integer stock;  // 재고
    public void incrementRestockRound() {
        this.totalRestockRound++;
    }

    public Product(Integer totalRestockRound, String name, Integer stock) {
        this.totalRestockRound = totalRestockRound;
        this.name = name;
        this.stock = stock;
    }
}
