package com.easydora.orders.consumer;

import com.easydora.orders.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * inventory-service now publishes the full StockReservedEvent/
 * StockInsufficientEvent JSON (orderId, success/message or
 * productId/required/available, timestamp) instead of a bare orderId
 * string, so this consumer parses the payload as JSON. The Kafka listener
 * parameter stays typed as String rather than switching to a value class,
 * since orders-service has no JsonDeserializer/type-mapping configured on
 * its Kafka consumer factory (it relies on Spring's default
 * StringDeserializer) — introducing that would affect every @KafkaListener
 * in the service, which is out of scope here.
 */
@Component
public class InventoryEventsConsumer {

    private final OrderService orderService;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(InventoryEventsConsumer.class);

    public InventoryEventsConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(topics = "stock-reserved")
    public void handleStockReserved(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        String orderId = event.get("orderId").asText();
        logger.info("[KAFKA] StockReservedEvent received: orderId={}, success={}, message={}",
                orderId, event.path("success").asBoolean(), event.path("message").asText(null));
        orderService.handleInventoryReserved(orderId);
    }

    @KafkaListener(topics = "stock-insufficient")
    public void handleStockInsufficient(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        String orderId = event.get("orderId").asText();
        logger.info("[KAFKA] StockInsufficientEvent received: orderId={}, productId={}, required={}, available={}",
                orderId,
                event.path("productId").asText(null),
                event.path("required").asInt(-1),
                event.path("available").asInt(-1));
        orderService.handleInventoryFailed(orderId);
    }
}