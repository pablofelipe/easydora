package com.easydora.products.event;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ProductDeletedEvent {
    
    @JsonProperty("productId")
    private String productId;
    
    @JsonProperty("deletedAt")
    private String deletedAt;
    
    // Getters and Setters
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    
    public String getDeletedAt() { return deletedAt; }
    public void setDeletedAt(String deletedAt) { this.deletedAt = deletedAt; }
}