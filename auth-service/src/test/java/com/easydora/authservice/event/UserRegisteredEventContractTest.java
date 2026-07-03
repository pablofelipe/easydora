package com.easydora.authservice.event;

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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the UserRegisteredEvent this service publishes on RabbitMQ
 * (auth.exchange, routing key user.registered) must conform to the schema
 * shared across services at
 * /schemas/json/UserRegisteredEvent.schema.json. orders-service and
 * products-service run the same check against their own copy of this DTO
 * (both named UserEvent in their own packages), since there is no shared
 * DTO library in this codebase.
 */
class UserRegisteredEventContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void publishedEventConformsToSharedSchema() throws IOException {
        UserRegisteredEvent event = new UserRegisteredEvent(
                42L, "buyer@example.com", "Ana", "Silva", "BUYER", "verif-token-123");

        JsonNode payload = MAPPER.valueToTree(event);
        Set<ValidationMessage> errors = loadSchema().validate(payload);

        assertThat(errors)
                .withFailMessage("published UserRegisteredEvent violates shared schema: %s", errors)
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
