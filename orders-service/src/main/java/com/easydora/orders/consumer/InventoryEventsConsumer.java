package com.easydora.orders.consumer;

import com.easydora.orders.service.OrderService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventsConsumer {
    
    private final OrderService orderService;
    private static final Logger logger = LoggerFactory.getLogger(InventoryEventsConsumer.class);

    public InventoryEventsConsumer(OrderService orderService) {
        this.orderService = orderService;
    }
    
    @KafkaListener(topics = "stock-reserved")
    public void handleStockReserved(String orderId) {
        logger.info("🔧 [KAFKA] Chamando orderService.handleStockReserved para: {}", orderId);
        orderService.handleInventoryReserved(orderId);
    }
    
    @KafkaListener(topics = "stock-insufficient")
    public void handleStockInsufficient(String orderId) {
        logger.info("🔧 [KAFKA] Chamando orderService.handleInventoryFailed para: {}", orderId);
        orderService.handleInventoryFailed(orderId);
    }
}