package com.easydora.orders.repository;

import com.easydora.orders.entity.Order;
import com.easydora.orders.statemachine.OrderState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);
    
    Optional<Order> findByIdAndUserId(String id, Long userId);
    
    @Query("SELECT o FROM Order o WHERE o.state = :state")
    List<Order> findByState(@Param("state") OrderState state);
    
    long countByUserId(Long userId);
}