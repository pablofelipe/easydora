package com.easydora.billing.exception;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String orderId) {
        super("Payment not found for order: " + orderId);
    }
}
