package com.easydora.billing.messaging.events;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class OrderCreatedEvent {
    private String orderId;
    private Long userId;
    private BigDecimal totalAmount;
    private List<OrderItem> items;
    private LocalDateTime createdAt;

    public static class OrderItem {
        private String productId;
        private Integer quantity;
        private BigDecimal unitPrice;

        public String getProductId() { return productId; }
        public void setProductId(String productId) { this.productId = productId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    }

    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }

    public String getOrderId() {
        return orderId;
    }
    public void setOrderId(String orderId) {
        this.orderId = orderId;
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
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }


}