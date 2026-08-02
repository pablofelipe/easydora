package com.easydora.billing.messaging.events;

// Mirrors orders-service's own OrderStatusChangedEvent field-for-field,
// deserialized here as plain Strings (previousState/newState) rather than
// orders-service's own OrderState enum -- this service has no reason to
// share that type, and only ever compares newState against one literal,
// "INVENTORY_RESERVED" (see PaymentService).
public class OrderStatusChangedEvent {
    private String orderId;
    private String previousState;
    private String newState;

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getPreviousState() {
        return previousState;
    }

    public void setPreviousState(String previousState) {
        this.previousState = previousState;
    }

    public String getNewState() {
        return newState;
    }

    public void setNewState(String newState) {
        this.newState = newState;
    }
}
