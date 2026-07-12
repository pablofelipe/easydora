package com.easydora.orders.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the jwt.created payload this service consumes
 * (UserEventsConsumer.handleJwtCreated) must conform to
 * /schemas/json/jwt-created.schema.json.
 */
class JwtCreatedConsumerContractTest {

    @Test
    void consumedEventConformsToSharedSchema() throws IOException {
        UserEvent event = new UserEvent();
        event.setToken("jwt-token-value");
        event.setUserId(42L);
        event.setEmail("buyer@example.com");
        event.setFirstName("Ana");
        event.setLastName("Silva");
        event.setRole("BUYER");
        event.setCreatedAt(LocalDateTime.parse("2026-07-13T10:00:00"));
        event.setExpiresIn(3600L);

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("jwt-created.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("orders-service's UserEvent (jwt.created) violates shared schema: %s", errors)
                .isEmpty();
    }
}
