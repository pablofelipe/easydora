package com.easydora.billing.messaging.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: payment.approved (RabbitMQ order.exchange), published by
 * PaymentService.publishPaymentEvent. Consumed by orders-service.
 */
class PaymentApprovedContractTest {

    @Test
    void publishedEventConformsToSharedSchema() throws IOException {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId("order-1");
        event.setTransactionId("TXN_abc123");

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("payment-approved.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published PaymentEvent (approved) violates shared schema: %s", errors)
                .isEmpty();
    }
}
