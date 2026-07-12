package com.easydora.orders.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for an intentionally partial consumer (see this package's
 * own ProductCreatedEvent -- only productId/sellerId, by design). Unlike a
 * symmetric consumer, this cannot serialize-its-own-DTO-then-validate: a
 * DTO with only 2 of 6 fields would always fail the full producer schema's
 * required list, for no real contract violation. Instead: start from a
 * schema-conformant example payload (what the real producer sends),
 * deserialize it into this service's own DTO, and assert the subset of
 * fields it does declare came through correctly -- proving this consumer's
 * assumption about those two fields' names/types still holds, without
 * demanding it capture fields it deliberately ignores.
 */
class ProductCreatedConsumerContractTest {

    @Test
    void ownedFieldsAreCorrectlyExtractedFromASchemaConformantPayload() throws IOException {
        String wirePayload = "{"
                + "\"productId\":\"prod-1\","
                + "\"productName\":\"Widget\","
                + "\"sellerId\":\"seller-1\","
                + "\"price\":19.90,"
                + "\"initialStock\":100,"
                + "\"createdAt\":\"2026-07-13T10:00:00Z\""
                + "}";

        JsonNode payload = SchemaContractSupport.MAPPER.readTree(wirePayload);
        JsonSchema schema = SchemaContractSupport.loadSchema("product-created.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);
        assertThat(errors)
                .withFailMessage("example product.created payload does not even match its own schema: %s", errors)
                .isEmpty();

        ProductCreatedEvent event = SchemaContractSupport.MAPPER.treeToValue(payload, ProductCreatedEvent.class);

        assertThat(event.getProductId()).isEqualTo("prod-1");
        assertThat(event.getSellerId()).isEqualTo("seller-1");
    }
}
