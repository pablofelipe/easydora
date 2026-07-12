package com.easydora.authservice.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: user.verified (RabbitMQ auth.exchange), published by
 * UserService.verifyEmail via OutboxEnvelopeCodec.wrap(..., String.valueOf
 * (user.getId())). Unlike every other event here, the real wire body is a
 * bare JSON integer -- not an object -- since OutboxPublisher sends
 * envelope.body() as-is. This test reproduces exactly that: String.valueOf
 * of a Long, parsed the same way the real message bytes would be.
 */
class UserVerifiedContractTest {

    @Test
    void publishedEventConformsToSharedSchema() throws IOException {
        String wireBody = String.valueOf(42L);

        JsonNode payload = SchemaContractSupport.MAPPER.readTree(wireBody);
        JsonSchema schema = SchemaContractSupport.loadSchema("user-verified.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published user.verified body violates shared schema: %s", errors)
                .isEmpty();
    }
}
