package com.easydora.orders.dto;

import java.math.BigDecimal;

public class OrderItemResponse {
    
    private String id;
    private String productId;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public String getProductId() {
        return productId;
    }
    public void setProductId(String productId) {
        this.productId = productId;
    }
    public Integer getQuantity() {
        return quantity;
    }
    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }
    public BigDecimal getSubtotal() {
        return subtotal;
    }
    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }
}