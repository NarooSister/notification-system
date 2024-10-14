package com.sparta.notificationsystem.product.repository;

import com.sparta.notificationsystem.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
