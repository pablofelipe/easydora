package com.easydora.orders.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the payment.failed payload this service consumes
 * (PaymentEventsConsumer.onPaymentFailed) must conform to
 * /schemas/json/payment-failed.schema.json.
 */
class PaymentFailedConsumerContractTest {

    @Test
    void consumedEventConformsToSharedSchema() throws IOException {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId("order-2");
        event.setFailureReason("Payment declined by the processor");

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("payment-failed.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("orders-service's PaymentEvent (failed) violates shared schema: %s", errors)
                .isEmpty();
    }
}
