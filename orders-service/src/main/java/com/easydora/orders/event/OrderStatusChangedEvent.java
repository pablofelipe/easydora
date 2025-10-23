package com.easydora.orders.event;

import com.easydora.orders.statemachine.OrderState;

public class OrderStatusChangedEvent {
    private String orderId;
    private OrderState previousState;
    private OrderState newState;
    public String getOrderId() {
        return orderId;
    }
    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
    public OrderState getPreviousState() {
        return previousState;
    }
    public void setPreviousState(OrderState previousState) {
        this.previousState = previousState;
    }
    public OrderState getNewState() {
        return newState;
    }
    public void setNewState(OrderState newState) {
        this.newState = newState;
    }
   
}