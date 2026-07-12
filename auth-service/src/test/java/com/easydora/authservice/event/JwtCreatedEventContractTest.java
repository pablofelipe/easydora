package com.easydora.authservice.event;

import com.easydora.authservice.dto.JwtCreatedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: jwt.created (RabbitMQ auth.exchange), published directly
 * by RabbitMQProducerService.sendJwtCreatedEvent on every login. userId is
 * Long here -- see ADR-0002's Update for a real drift found and fixed
 * where this field used to be a raw String (the JWT subject claim), while
 * every consumer already treated it as numeric.
 */
class JwtCreatedEventContractTest {

    @Test
    void publishedEventConformsToSharedSchema() throws IOException {
        JwtCreatedEvent event = new JwtCreatedEvent(
                "jwt-token-value", 42L, "buyer@example.com", "Ana", "Silva", "BUYER",
                LocalDateTime.parse("2026-07-13T10:00:00"), 3600L);

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("jwt-created.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published JwtCreatedEvent violates shared schema: %s", errors)
                .isEmpty();
    }
}
