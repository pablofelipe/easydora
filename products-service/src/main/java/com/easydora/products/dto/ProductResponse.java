package com.easydora.products.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ProductResponse {
    private String id;
    private String name;
    private String description;
    private BigDecimal price;
    private SellerInfo seller;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Constructors, Getters and Setters
    public ProductResponse() {}
    public ProductResponse(String id, String name, String description, BigDecimal price, SellerInfo seller, Boolean active, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.seller = seller;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ... getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public SellerInfo getSeller() { return seller; }
    public void setSeller(SellerInfo seller) { this.seller = seller; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static class SellerInfo {
        private String userId;
        private String name;
        private String avatarUrl;
        
        // Constructors, Getters and Setters
        public SellerInfo() {}
        
        public SellerInfo(String userId, String name, String avatarUrl) {
            this.userId = userId;
            this.name = name;
            this.avatarUrl = avatarUrl;
        }
        
        // ... getters and setters
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getAvatarUrl() { return avatarUrl; }
        public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    }
}