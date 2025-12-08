package com.easydora.products.event;

import java.math.BigDecimal;

public class ProductCreatedEvent {
    private String productId;
    private String productName;
    private String sellerId;
    private BigDecimal price;
    private Integer initialStock;
    private String createdAt;
    public String getProductId() {
        return productId;
    }
    public void setProductId(String productId) {
        this.productId = productId;
    }
    public String getProductName() {
        return productName;
    }
    public void setProductName(String productName) {
        this.productName = productName;
    }
    public String getSellerId() {
        return sellerId;
    }
    public void setSellerId(String sellerId) {
        this.sellerId = sellerId;
    }
    public BigDecimal getPrice() {
        return price;
    }
    public void setPrice(BigDecimal price) {
        this.price = price;
    }
    public Integer getInitialStock() {
        return initialStock;
    }
    public void setInitialStock(Integer initialStock) {
        this.initialStock = initialStock;
    }
    public String getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}