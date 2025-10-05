package com.easydora.products.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.easydora.products.entity.Product;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, String> {
    List<Product> findBySellerUserIdAndActiveTrue(String sellerId);
    List<Product> findByActiveTrue();
    Optional<Product> findByIdAndActiveTrue(String id);
    boolean existsByIdAndSellerUserId(String id, String sellerId);
}