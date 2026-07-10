package com.easydora.orders.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Answers exactly one question -- "does this seller own this product?" --
 * for the self-purchase check in OrderService.createOrder. Deliberately
 * does not mirror products-service's catalog (name, price, stock,
 * description, category, timestamps): those attributes have no bearing on
 * that one question, and copying them would turn this into a second,
 * drifting copy of the catalog instead of a narrow projection.
 */
@Entity
@Table(name = "product_ownership", schema = "orders_schema")
public class ProductOwnership {

    @Id
    @Column(name = "product_id")
    private String productId;

    @Column(name = "seller_id", nullable = false)
    private String sellerId;

    public ProductOwnership() {
    }

    public ProductOwnership(String productId, String sellerId) {
        this.productId = productId;
        this.sellerId = sellerId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getSellerId() {
        return sellerId;
    }

    public void setSellerId(String sellerId) {
        this.sellerId = sellerId;
    }
}
