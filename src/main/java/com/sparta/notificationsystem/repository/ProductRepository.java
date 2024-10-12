package com.sparta.notificationsystem.repository;

import com.sparta.notificationsystem.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
