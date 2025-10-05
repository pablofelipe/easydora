package com.easydora.products.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.easydora.products.entity.Seller;

@Repository
public interface SellerRepository extends JpaRepository<Seller, String> {
    boolean existsByUserIdAndActiveTrue(String userId);
}