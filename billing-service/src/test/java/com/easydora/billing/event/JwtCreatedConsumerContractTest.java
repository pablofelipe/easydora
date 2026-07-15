package com.easydora.billing.event;

import com.easydora.billing.messaging.events.SchemaContractSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for JwtEvent (this package), which now declares every
 * field the shared jwt-created schema requires: createdAt/expiresIn were
 * added so this service's JwtAuthenticationFilter can give each cache
 * entry a lifetime equal to the JWT's own expiresIn instead of none at all
 * (ADR-0039) -- previously deliberately ignored, the same
 * intentional-partial-consumer pattern notification-service's
 * firstName/lastName addition already went through.
 */
class JwtCreatedConsumerContractTest {

    @Test
    void ownedFieldsAreCorrectlyExtractedFromASchemaConformantPayload() throws IOException {
        String wirePayload = "{"
                + "\"token\":\"jwt-token-value\","
                + "\"userId\":42,"
                + "\"email\":\"buyer@example.com\","
                + "\"firstName\":\"Ana\","
                + "\"lastName\":\"Silva\","
                + "\"role\":\"BUYER\","
                + "\"createdAt\":\"2026-07-13T10:00:00\","
                + "\"expiresIn\":3600"
                + "}";

        JsonNode payload = SchemaContractSupport.MAPPER.readTree(wirePayload);
        JsonSchema schema = SchemaContractSupport.loadSchema("jwt-created.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);
        assertThat(errors)
                .withFailMessage("example jwt.created payload does not even match its own schema: %s", errors)
                .isEmpty();

        JwtEvent event = SchemaContractSupport.MAPPER.treeToValue(payload, JwtEvent.class);

        assertThat(event.getToken()).isEqualTo("jwt-token-value");
        assertThat(event.getUserId()).isEqualTo(42L);
        assertThat(event.getEmail()).isEqualTo("buyer@example.com");
        assertThat(event.getFirstName()).isEqualTo("Ana");
        assertThat(event.getLastName()).isEqualTo("Silva");
        assertThat(event.getRole()).isEqualTo("BUYER");
        assertThat(event.getCreatedAt()).isEqualTo(java.time.LocalDateTime.parse("2026-07-13T10:00:00"));
        assertThat(event.getExpiresIn()).isEqualTo(3600L);
    }
}
