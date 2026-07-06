package com.easydora.orders.consumer;

import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * inventory-service's Outbox (ADR-0007) publishes the full
 * StockReservedEvent/StockInsufficientEvent JSON (orderId, success/message
 * or productId/required/available, timestamp) as the raw outbox payload,
 * so this consumer parses it as JSON. The @RabbitListener entry points
 * take the raw AMQP Message and decode the body as a UTF-8 string
 * themselves — the shared Jackson2JsonMessageConverter (content-type
 * application/json) tries to JSON-parse the body into its inferred target
 * type, and fails with a MismatchedInputException for a String target
 * since the body is a JSON object, not a JSON string literal. Delegating
 * to handleStockReserved/handleStockInsufficient keeps those methods'
 * String payload signature exactly as the existing behavioral test
 * expects.
 */
@Component
public class InventoryEventsConsumer {

    private final OrderService orderService;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(InventoryEventsConsumer.class);

    public InventoryEventsConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @RabbitListener(queues = RabbitMQConfig.STOCK_RESERVED_QUEUE)
    public void onStockReserved(Message message) throws Exception {
        handleStockReserved(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    public void handleStockReserved(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        String orderId = event.get("orderId").asText();
        logger.info("[RABBITMQ] StockReservedEvent received: orderId={}, success={}, message={}",
                orderId, event.path("success").asBoolean(), event.path("message").asText(null));
        orderService.handleInventoryReserved(orderId);
    }

    @RabbitListener(queues = RabbitMQConfig.STOCK_INSUFFICIENT_QUEUE)
    public void onStockInsufficient(Message message) throws Exception {
        handleStockInsufficient(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    public void handleStockInsufficient(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        String orderId = event.get("orderId").asText();
        logger.info("[RABBITMQ] StockInsufficientEvent received: orderId={}, productId={}, required={}, available={}",
                orderId,
                event.path("productId").asText(null),
                event.path("required").asInt(-1),
                event.path("available").asInt(-1));
        orderService.handleInventoryFailed(orderId);
    }
}