package com.easydora.billing.exception;

public class OrderNotReadyForPaymentException extends RuntimeException {

    public OrderNotReadyForPaymentException(String orderId, String actualState) {
        super("Order " + orderId + " is not ready for payment (state: "
                + (actualState == null ? "unknown" : actualState) + ", expected INVENTORY_RESERVED)");
    }
}
