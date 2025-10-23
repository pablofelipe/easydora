package com.easydora.orders.statemachine;

public enum OrderEvent {
    PAYMENT_RECEIVED,
    PAYMENT_FAILED,
    INVENTORY_RESERVED,
    INVENTORY_FAILED,
    SHIP_ORDER,
    DELIVER_ORDER,
    CANCEL_ORDER
}