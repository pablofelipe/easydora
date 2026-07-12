package com.easydora.products.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the user.verified payload this service consumes
 * (UserEventConsumer.handleUserVerified(Long userId, ...)) must conform to
 * /schemas/json/user-verified.schema.json -- a bare JSON integer, not an
 * object, matching the listener's own Long parameter.
 */
class UserVerifiedConsumerContractTest {

    @Test
    void consumedEventConformsToSharedSchema() throws IOException {
        JsonNode payload = SchemaContractSupport.MAPPER.readTree(String.valueOf(42L));
        JsonSchema schema = SchemaContractSupport.loadSchema("user-verified.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("products-service's user.verified consumption violates shared schema: %s", errors)
                .isEmpty();
    }
}
