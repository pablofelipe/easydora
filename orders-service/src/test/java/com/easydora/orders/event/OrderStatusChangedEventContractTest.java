package com.easydora.orders.event;

import com.easydora.orders.statemachine.OrderState;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: order.status-changed (RabbitMQ order.exchange), published
 * by OrderService.publishOrderStatusChanged on every state-machine
 * transition. Consumed by notification-service.
 */
class OrderStatusChangedEventContractTest {

    @Test
    void publishedEventConformsToSharedSchema() throws IOException {
        OrderStatusChangedEvent event = new OrderStatusChangedEvent();
        event.setOrderId("order-1");
        event.setPreviousState(OrderState.INVENTORY_RESERVED);
        event.setNewState(OrderState.PAYMENT_APPROVED);

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("order-status-changed.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published OrderStatusChangedEvent violates shared schema: %s", errors)
                .isEmpty();
    }
}
