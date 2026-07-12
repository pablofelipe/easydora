package com.easydora.orders.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the payment.refund.failed payload this service consumes
 * (PaymentEventsConsumer.onPaymentRefundFailed, ADR-0034) must conform to
 * /schemas/json/payment-refund-failed.schema.json.
 */
class PaymentRefundFailedConsumerContractTest {

    @Test
    void consumedEventConformsToSharedSchema() throws IOException {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId("order-4");
        event.setFailureReason("Payment not found for order order-4");

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("payment-refund-failed.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("orders-service's PaymentEvent (refund failed) violates shared schema: %s", errors)
                .isEmpty();
    }
}
