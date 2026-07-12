package com.easydora.billing.messaging.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: payment.refunded (RabbitMQ order.exchange, ADR-0034),
 * published by PaymentService.publishRefunded. Consumed by orders-service.
 */
class PaymentRefundedContractTest {

    @Test
    void publishedEventConformsToSharedSchema() throws IOException {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId("order-3");
        event.setTransactionId("TXN_original");

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(event);
        JsonSchema schema = SchemaContractSupport.loadSchema("payment-refunded.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published PaymentEvent (refunded) violates shared schema: %s", errors)
                .isEmpty();
    }
}
