package com.easydora.orders.entity;

import com.easydora.orders.statemachine.OrderState;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders", schema = "orders_schema")
public class Order {
    
    @Id
    private String id;
    
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderState state;
    
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
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


    public List<OrderItem> getItems() {
        return items;
    }


    public void setItems(List<OrderItem> items) {
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


    // Constructors, Getters, Setters
    public Order() {}
    
    
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
    
    public BigDecimal calculateTotal() {
        return items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}