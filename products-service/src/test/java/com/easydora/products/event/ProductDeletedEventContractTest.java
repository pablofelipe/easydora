package com.easydora.products.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: product.deleted (RabbitMQ product.exchange), published by
 * ProductService. Consumed by inventory-service.
 */
class ProductDeletedEventContractTest {

    @Test
    void publishedEventConformsToSharedSchema() throws IOException {
        ProductDeletedEvent event = new ProductDeletedEvent();
        event.setProductId("prod-1");
        event.setDeletedAt("2026-07-13T10:00:00Z");

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("product-deleted.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published ProductDeletedEvent violates shared schema: %s", errors)
                .isEmpty();
    }
}
