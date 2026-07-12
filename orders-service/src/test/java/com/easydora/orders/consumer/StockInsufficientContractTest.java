package com.easydora.orders.consumer;

import com.easydora.orders.event.SchemaContractSupport;
import com.easydora.orders.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Contract test: stock.insufficient (RabbitMQ order.exchange), published by
 * inventory-service's Outbox. Same raw-JsonNode consumer strategy as
 * StockReservedContractTest.
 */
@ExtendWith(MockitoExtension.class)
class StockInsufficientContractTest {

    @Mock
    private OrderService orderService;

    @Test
    void consumedEventConformsToSharedSchemaAndIsHandledCorrectly() throws Exception {
        String wirePayload = "{\"orderId\":\"order-2\",\"productId\":\"prod-1\","
                + "\"required\":5,\"available\":2,\"timestamp\":\"2026-07-13T10:00:00Z\"}";

        JsonNode payload = SchemaContractSupport.MAPPER.readTree(wirePayload);
        JsonSchema schema = SchemaContractSupport.loadSchema("stock-insufficient.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);
        assertThat(errors)
                .withFailMessage("example stock.insufficient payload violates its own shared schema: %s", errors)
                .isEmpty();

        new InventoryEventsConsumer(orderService).handleStockInsufficient(wirePayload);

        verify(orderService).handleInventoryFailed("order-2");
    }
}
