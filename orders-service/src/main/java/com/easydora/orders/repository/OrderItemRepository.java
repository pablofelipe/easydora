package com.easydora.orders.repository;

import com.easydora.orders.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, String> {
    
    List<OrderItem> findByOrderId(String orderId);
    
    List<OrderItem> findByProductId(String productId);
}