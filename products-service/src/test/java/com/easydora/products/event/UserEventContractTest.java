package com.easydora.products.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the UserEvent this service consumes from RabbitMQ
 * (auth.exchange, routing key user.registered, published by auth-service as
 * UserRegisteredEvent) must conform to the schema shared across services at
 * /schemas/json/UserRegisteredEvent.schema.json.
 *
 * userId used to be typed String here, while auth-service (the publisher)
 * sends it as a number (Long) — the catalogued type drift, fixed to Long.
 */
class UserEventContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void consumedEventConformsToSharedSchema() throws IOException {
        UserEvent event = new UserEvent();
        event.setUserId(42L);
        event.setEmail("buyer@example.com");
        event.setFirstName("Ana");
        event.setLastName("Silva");
        event.setRole("BUYER");
        event.setVerificationToken("verif-token-123");
        event.setCreatedAt(LocalDateTime.parse("2026-07-03T10:00:00"));

        JsonNode payload = MAPPER.valueToTree(event);
        Set<ValidationMessage> errors = loadSchema().validate(payload);

        assertThat(errors)
                .withFailMessage("products-service's UserEvent violates shared schema: %s", errors)
                .isEmpty();
    }

    private JsonSchema loadSchema() throws IOException {
        Path schemaPath = resolveSchemaPath("UserRegisteredEvent.schema.json");
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
