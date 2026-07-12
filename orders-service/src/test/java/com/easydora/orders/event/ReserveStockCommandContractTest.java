package com.easydora.orders.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: stock.reserve (RabbitMQ order.exchange), published by
 * OrderService.sendReserveStockCommand. A command, not a fact-event
 * (ADR-0034's naming precedent) -- consumed by inventory-service.
 */
class ReserveStockCommandContractTest {

    @Test
    void publishedCommandConformsToSharedSchema() throws IOException {
        ReserveStockCommand command = new ReserveStockCommand();
        command.setOrderId("order-1");

        ReserveStockCommand.OrderItemDTO item = new ReserveStockCommand.OrderItemDTO();
        item.setProductId("prod-1");
        item.setQuantity(2);
        command.setItems(List.of(item));

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(command);
        JsonSchema schema = SchemaContractSupport.loadSchema("stock-reserve.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published ReserveStockCommand violates shared schema: %s", errors)
                .isEmpty();
    }
}
