package com.easydora.orders.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the payment.approved payload this service consumes
 * (PaymentEventsConsumer.onPaymentApproved) must conform to
 * /schemas/json/payment-approved.schema.json.
 */
class PaymentApprovedConsumerContractTest {

    @Test
    void consumedEventConformsToSharedSchema() throws IOException {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId("order-1");
        event.setTransactionId("txn-1");

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("payment-approved.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("orders-service's PaymentEvent (approved) violates shared schema: %s", errors)
                .isEmpty();
    }
}
