package com.easydora.orders.exception;

public class OrderNotFoundException extends Exception {
    private final String orderId;

    public OrderNotFoundException(String orderId) {
        this.orderId = orderId;
    }

    @Override
    public String getMessage() {
        return "Order with ID " + orderId + " not found.";
    }
    
}
