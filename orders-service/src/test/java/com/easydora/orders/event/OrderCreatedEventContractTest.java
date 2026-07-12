package com.easydora.orders.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the OrderCreatedEvent this service publishes on RabbitMQ
 * (order.exchange, routing key order.created) must conform to the schema
 * shared across services at /schemas/json/order-created.schema.json.
 * billing-service runs the same check against its own copy of this DTO,
 * since there is no shared DTO library in this codebase.
 */
class OrderCreatedEventContractTest {

    // WRITE_DATES_AS_TIMESTAMPS is disabled to match production behavior:
    // Spring's Jackson2JsonMessageConverter (used by the real RabbitMQ
    // producer) also disables it, serializing java.time types as ISO-8601
    // strings instead of numeric arrays.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void publishedEventConformsToSharedSchema() throws IOException {
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId("order-123");
        event.setUserId(42L);
        event.setTotalAmount(new BigDecimal("99.90"));
        event.setCreatedAt(Instant.parse("2026-07-03T10:00:00Z"));

        OrderCreatedEvent.OrderItem item = new OrderCreatedEvent.OrderItem();
        item.setProductId("prod-1");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("49.95"));
        event.setItems(List.of(item));

        JsonNode payload = MAPPER.valueToTree(event);
        Set<ValidationMessage> errors = loadSchema().validate(payload);

        assertThat(errors)
                .withFailMessage("published OrderCreatedEvent violates shared schema: %s", errors)
                .isEmpty();
    }

    private JsonSchema loadSchema() throws IOException {
        Path schemaPath = resolveSchemaPath("order-created.schema.json");
        JsonNode schemaNode = MAPPER.readTree(Files.newBufferedReader(schemaPath));
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
    }

    private Path resolveSchemaPath(String fileName) {
        Path fromServiceDir = Paths.get("..", "schemas", "json", fileName);
        if (Files.exists(fromServiceDir)) {
            return fromServiceDir;
        }
        Path fromRepoRoot = Paths.get("schemas", "json", fileName);
        if (Files.exists(fromRepoRoot)) {
            return fromRepoRoot;
        }
        throw new IllegalStateException(
                "Shared schema " + fileName + " not found relative to " + Paths.get("").toAbsolutePath());
    }
}
