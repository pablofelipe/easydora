package com.easydora.correlation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Wraps/unwraps the Outbox-internal envelope described by
 * {@link OutboxEnvelope}. {@code body} is always stored and returned as
 * the exact original payload text -- whether that text is itself a JSON
 * object (e.g. UserRegisteredEvent) or a bare, non-JSON-object string
 * (e.g. user.verified's raw numeric userId) makes no difference here,
 * since it is carried as an opaque JSON string value either way.
 */
public final class OutboxEnvelopeCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OutboxEnvelopeCodec() {
    }

    public static String wrap(String correlationId, String messageId, String traceparent, String body) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("correlationId", correlationId);
        node.put("messageId", messageId);
        node.put("traceparent", traceparent);
        node.put("body", body);
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode outbox envelope", e);
        }
    }

    public static OutboxEnvelope unwrap(String stored) {
        try {
            JsonNode node = MAPPER.readTree(stored);
            return new OutboxEnvelope(
                    node.path("correlationId").asText(null),
                    node.path("messageId").asText(null),
                    node.path("traceparent").asText(null),
                    node.path("body").asText(null)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to decode outbox envelope", e);
        }
    }
}
