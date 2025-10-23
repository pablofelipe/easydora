package com.easydora.orders.repository;

import com.easydora.orders.entity.Buyer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BuyerRepository extends JpaRepository<Buyer, Long> {
    
    Optional<Buyer> findByEmail(String email);
    
    boolean existsByUserIdAndActiveTrue(Long userId);
}