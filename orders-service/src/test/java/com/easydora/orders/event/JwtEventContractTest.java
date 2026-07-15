package com.easydora.orders.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for JwtEvent, consumed independently by JwtConsumer (the
 * session/auth-cache side of orders-service's jwt.created fanout -- see
 * JwtCreatedFanoutBehaviorTest). UserEvent (the profile side, consumed by
 * UserEventsConsumer.handleJwtCreated) already has its own contract test in
 * this same package. JwtEvent now declares every field the shared schema
 * requires: createdAt/expiresIn were added so JwtAuthenticationFilter can
 * give each cache entry a lifetime equal to the JWT's own expiresIn
 * (ADR-0039) instead of none at all.
 */
class JwtEventContractTest {

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
