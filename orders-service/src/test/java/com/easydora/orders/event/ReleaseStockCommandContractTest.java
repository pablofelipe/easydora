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
 * Contract test: stock.release (RabbitMQ order.exchange), published by
 * ReleaseInventoryAction. A command, not a fact-event -- consumed by
 * inventory-service.
 */
class ReleaseStockCommandContractTest {

    @Test
    void publishedCommandConformsToSharedSchema() throws IOException {
        ReleaseStockCommand command = new ReleaseStockCommand();
        command.setOrderId("order-1");

        ReleaseStockCommand.OrderItemDTO item = new ReleaseStockCommand.OrderItemDTO();
        item.setProductId("prod-1");
        item.setQuantity(2);
        command.setItems(List.of(item));

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(command);
        JsonSchema schema = SchemaContractSupport.loadSchema("stock-release.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published ReleaseStockCommand violates shared schema: %s", errors)
                .isEmpty();
    }
}
