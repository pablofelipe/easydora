package com.easydora.billing.messaging.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared boilerplate for this service's *ContractTest classes --
 * deduplicates the schema-loading logic each one already needed
 * individually (see OrderCreatedEventContractTest, the original), not a new
 * abstraction: every contract test still owns its own assertion and its own
 * example payload. FAIL_ON_UNKNOWN_PROPERTIES is disabled to mirror the
 * real Jackson2JsonMessageConverter config (RabbitMQConfig) every consumer
 * in this service actually runs under.
 */
public final class SchemaContractSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private SchemaContractSupport() {
    }

    public static JsonSchema loadSchema(String fileName) throws IOException {
        Path schemaPath = resolveSchemaPath(fileName);
        JsonNode schemaNode = MAPPER.readTree(Files.newBufferedReader(schemaPath));
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
    }

    private static Path resolveSchemaPath(String fileName) {
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
