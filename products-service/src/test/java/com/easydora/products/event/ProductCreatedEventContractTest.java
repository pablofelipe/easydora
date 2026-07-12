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
 * Contract test: product.created (RabbitMQ product.exchange), published by
 * ProductService. Consumed by inventory-service and orders-service.
 */
class ProductCreatedEventContractTest {

    @Test
    void publishedEventConformsToSharedSchema() throws IOException {
        ProductCreatedEvent event = new ProductCreatedEvent();
        event.setProductId("prod-1");
        event.setProductName("Widget");
        event.setSellerId("seller-1");
        event.setPrice(new BigDecimal("19.90"));
        event.setInitialStock(100);
        event.setCreatedAt("2026-07-13T10:00:00Z");

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("product-created.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published ProductCreatedEvent violates shared schema: %s", errors)
                .isEmpty();
    }
}
