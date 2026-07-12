package com.easydora.products.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: product.updated (RabbitMQ product.exchange), published by
 * ProductService. Consumed by inventory-service.
 */
class ProductUpdatedEventContractTest {

    @Test
    void publishedEventConformsToSharedSchema() throws IOException {
        ProductUpdatedEvent event = new ProductUpdatedEvent();
        event.setProductId("prod-1");
        event.setProductName("Widget");
        event.setPrice(new BigDecimal("24.90"));
        event.setActive(true);
        event.setUpdatedAt("2026-07-13T10:00:00Z");

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("product-updated.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published ProductUpdatedEvent violates shared schema: %s", errors)
                .isEmpty();
    }
}
