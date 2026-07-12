package com.easydora.billing.messaging.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the payment.refund.requested payload this service
 * consumes (OrderEventListener.handleRefundPaymentRequested, ADR-0034)
 * must conform to /schemas/json/payment-refund-requested.schema.json.
 */
class RefundPaymentCommandConsumerContractTest {

    @Test
    void consumedCommandConformsToSharedSchema() throws IOException {
        RefundPaymentCommand command = new RefundPaymentCommand();
        command.setOrderId("order-1");

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(command);
        JsonSchema schema = SchemaContractSupport.loadSchema("payment-refund-requested.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("billing-service's RefundPaymentCommand violates shared schema: %s", errors)
                .isEmpty();
    }
}
