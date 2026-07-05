package com.easydora.orders.statemachine;

public enum OrderState {
    PENDING,           // Order created, awaiting payment
    PAYMENT_APPROVED,  // Payment approved
    PAYMENT_FAILED,    // Payment failed
    PROCESSING,        // In processing
    INVENTORY_RESERVED,// Stock reserved
    INVENTORY_FAILED,  // Stock failure
    SHIPPED,           // Shipped
    DELIVERED,         // Delivered
    CANCELLED,         // Cancelled
    REFUNDING          // Refunding
}