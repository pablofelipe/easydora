package com.easydora.orders.dto;

import com.easydora.orders.statemachine.OrderState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class OrderResponse {
    
    private String id;
    private Long userId;
    private BigDecimal totalAmount;
    private OrderState state;
    private List<OrderItemResponse> items;
    private Instant createdAt;
    private Instant updatedAt;
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public Long getUserId() {
        return userId;
    }
    public void setUserId(Long userId) {
        this.userId = userId;
    }
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }
    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }
    public OrderState getState() {
        return state;
    }
    public void setState(OrderState state) {
        this.state = state;
    }
    public List<OrderItemResponse> getItems() {
        return items;
    }
    public void setItems(List<OrderItemResponse> items) {
        this.items = items;
    }
    public Instant getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}