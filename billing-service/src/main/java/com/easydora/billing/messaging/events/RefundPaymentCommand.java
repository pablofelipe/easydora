package com.easydora.billing.messaging.events;

/**
 * A command, not a fact-event, despite living on the same order.exchange as
 * order.created (ADR-0034): orders-service is instructing this service to
 * refund a specific payment, not just broadcasting a fact. See
 * com.easydora.orders.event.RefundPaymentCommand (orders-service's own copy
 * of this DTO, published from there) for the full rationale, including why
 * orderId is the only field.
 */
public class RefundPaymentCommand {
    private String orderId;

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
}
