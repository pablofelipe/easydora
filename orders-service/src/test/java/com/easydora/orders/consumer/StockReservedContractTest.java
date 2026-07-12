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
 * Contract test: stock.reserved (RabbitMQ order.exchange), published by
 * inventory-service's Outbox. This consumer (InventoryEventsConsumer) has
 * no fixed DTO -- it parses raw JsonNode -- so the contract check validates
 * a schema-conformant example payload, then feeds that exact text through
 * the real production method to prove the two are actually connected.
 */
@ExtendWith(MockitoExtension.class)
class StockReservedContractTest {

    @Mock
    private OrderService orderService;

    @Test
    void consumedEventConformsToSharedSchemaAndIsHandledCorrectly() throws Exception {
        String wirePayload = "{\"orderId\":\"order-1\",\"success\":true,"
                + "\"message\":\"stock reserved\",\"timestamp\":\"2026-07-13T10:00:00Z\"}";

        JsonNode payload = SchemaContractSupport.MAPPER.readTree(wirePayload);
        JsonSchema schema = SchemaContractSupport.loadSchema("stock-reserved.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);
        assertThat(errors)
                .withFailMessage("example stock.reserved payload violates its own shared schema: %s", errors)
                .isEmpty();

        new InventoryEventsConsumer(orderService).handleStockReserved(wirePayload);

        verify(orderService).handleInventoryReserved("order-1");
    }
}
