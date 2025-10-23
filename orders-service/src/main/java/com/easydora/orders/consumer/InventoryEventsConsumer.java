package com.easydora.orders.consumer;

import com.easydora.orders.service.OrderService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventsConsumer {
    
    private final OrderService orderService;
    
    public InventoryEventsConsumer(OrderService orderService) {
        this.orderService = orderService;
    }
    
    @KafkaListener(topics = "stock-reserved")
    public void handleStockReserved(String orderId) {
        orderService.handleInventoryReserved(orderId);
    }
    
    @KafkaListener(topics = "stock-insufficient")
    public void handleStockInsufficient(String orderId) {
        orderService.handleInventoryFailed(orderId);
    }
}