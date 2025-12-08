package com.easydora.products.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public class ProductUpdatedEvent {
    
    @JsonProperty("productId")
    private String productId;
    
    @JsonProperty("productName")
    private String productName;
    
    @JsonProperty("price")
    private BigDecimal price;
    
    @JsonProperty("active")
    private boolean active;
    
    @JsonProperty("updatedAt")
    private String updatedAt;
    
    // Getters and Setters
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}