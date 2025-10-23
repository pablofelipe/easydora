package com.easydora.orders.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class OrderRequest {
    
    @NotEmpty(message = "Items cannot be empty")
    @Valid
    private List<OrderItemRequest> items;
    
    // Getters and Setters
    public List<OrderItemRequest> getItems() { return items; }
    public void setItems(List<OrderItemRequest> items) { this.items = items; }
}