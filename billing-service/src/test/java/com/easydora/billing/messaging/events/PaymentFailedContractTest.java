package com.easydora.billing.messaging.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: payment.failed (RabbitMQ order.exchange), published by
 * PaymentService.publishPaymentEvent. Consumed by orders-service.
 */
class PaymentFailedContractTest {

    @Test
    void publishedEventConformsToSharedSchema() throws IOException {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId("order-2");
        event.setFailureReason("Odd amount rejected by the mock policy");

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("payment-failed.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published PaymentEvent (failed) violates shared schema: %s", errors)
                .isEmpty();
    }
}
