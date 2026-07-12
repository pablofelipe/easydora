package com.easydora.orders.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: payment.refund.requested (RabbitMQ order.exchange,
 * ADR-0034), published by OrderService.publishRefundPaymentCommand. A
 * command, not a fact-event -- consumed by billing-service.
 */
class RefundPaymentCommandContractTest {

    @Test
    void publishedCommandConformsToSharedSchema() throws IOException {
        RefundPaymentCommand command = new RefundPaymentCommand();
        command.setOrderId("order-1");

        JsonNode payload = SchemaContractSupport.MAPPER.valueToTree(command);
        JsonSchema schema = SchemaContractSupport.loadSchema("payment-refund-requested.schema.json");
        Set<ValidationMessage> errors = schema.validate(payload);

        assertThat(errors)
                .withFailMessage("published RefundPaymentCommand violates shared schema: %s", errors)
                .isEmpty();
    }
}
