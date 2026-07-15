package com.easydora.orders.consumer;

import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.entity.Order;
import com.easydora.orders.event.PaymentEvent;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.statemachine.OrderState;
import com.easydora.orders.support.OrderStatusChangedProbeSupport;
import com.easydora.orders.support.RefundPaymentRequestedProbeSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0034 end to end, against real Postgres/RabbitMQ (CI Phase 2 service
 * containers): a payment.approved that arrives for an order already
 * INVENTORY_FAILED/CANCELLED must move the order to REFUNDING and publish a
 * real RefundPaymentCommand; the two possible outcomes from Billing
 * (payment.refunded / payment.refund.failed) must close the loop into
 * REFUNDED/REFUND_FAILED; and a redelivered/duplicate payment.approved for
 * an order already compensating must not re-trigger anything. billing-service
 * itself is not involved -- this test publishes the same event shapes it
 * would, mirroring PaymentOutcomeWiringIT/StockOutcomeWiringIT's precedent
 * of proving each service's own side of a handshake independently.
 */
@SpringBootTest
@DirtiesContext
class PaymentCompensationWiringIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @ParameterizedTest
    @EnumSource(value = OrderState.class, names = {"INVENTORY_FAILED", "CANCELLED"})
    void aStrayPaymentApprovedInitiatesCompensation(OrderState terminalState) throws Exception {
        String orderId = seedOrder(terminalState);

        publishPaymentApproved(orderId);

        OrderState finalState = awaitState(orderId, terminalState);
        assertThat(finalState)
                .withFailMessage("order %s should have moved to REFUNDING after a stray payment.approved", orderId)
                .isEqualTo(OrderState.REFUNDING);

        assertProbeReceivedOrderStatusChanged(orderId, terminalState.name(), "REFUNDING");
        assertProbeReceivedRefundPaymentCommand(orderId);
    }

    @Test
    void paymentRefundedMovesTheOrderFromRefundingToRefunded() throws Exception {
        String orderId = seedOrder(OrderState.REFUNDING);

        PaymentEvent event = new PaymentEvent();
        event.setOrderId(orderId);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.PAYMENT_REFUNDED_ROUTING_KEY, event);

        OrderState finalState = awaitState(orderId, OrderState.REFUNDING);
        assertThat(finalState).isEqualTo(OrderState.REFUNDED);
        assertProbeReceivedOrderStatusChanged(orderId, "REFUNDING", "REFUNDED");
    }

    @Test
    void paymentRefundFailedMovesTheOrderFromRefundingToRefundFailed() throws Exception {
        String orderId = seedOrder(OrderState.REFUNDING);

        PaymentEvent event = new PaymentEvent();
        event.setOrderId(orderId);
        event.setFailureReason("Payment not found for order " + orderId);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.PAYMENT_REFUND_FAILED_ROUTING_KEY, event);

        OrderState finalState = awaitState(orderId, OrderState.REFUNDING);
        assertThat(finalState).isEqualTo(OrderState.REFUND_FAILED);
        assertProbeReceivedOrderStatusChanged(orderId, "REFUNDING", "REFUND_FAILED");
    }

    @Test
    void aRedeliveredPaymentApprovedForAnOrderAlreadyRefundingDoesNotReInitiateCompensation() throws Exception {
        String orderId = seedOrder(OrderState.INVENTORY_FAILED);

        publishPaymentApproved(orderId);
        OrderState afterFirst = awaitState(orderId, OrderState.INVENTORY_FAILED);
        assertThat(afterFirst).isEqualTo(OrderState.REFUNDING);
        drainRefundPaymentCommand(orderId);

        // A redelivered/duplicate payment.approved for the same order,
        // now already REFUNDING -- must be a no-op: no second
        // RefundPaymentCommand, no state change.
        publishPaymentApproved(orderId);
        Thread.sleep(1000);

        Order stillRefunding = orderRepository.findById(orderId).orElseThrow();
        assertThat(stillRefunding.getState()).isEqualTo(OrderState.REFUNDING);
        // 6s, not 1.5s: a real (buggy) duplicate write would only reach
        // this probe queue on the outbox poller's next tick, up to ~5s
        // later (ADR-0034 Update/ADR-0037) -- a short window here would
        // pass even if the bug this asserts against were reintroduced.
        assertThat(pollForRefundPaymentCommand(orderId, 6000))
                .withFailMessage("a duplicate payment.approved must not publish a second RefundPaymentCommand")
                .isFalse();
    }

    private String seedOrder(OrderState state) {
        String orderId = "it-" + UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(1L);
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setState(state);
        orderRepository.saveAndFlush(order);
        return orderId;
    }

    private void publishPaymentApproved(String orderId) {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId(orderId);
        event.setTransactionId("txn-" + UUID.randomUUID());
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.PAYMENT_APPROVED_ROUTING_KEY, event);
    }

    private OrderState awaitState(String orderId, OrderState initialState) throws InterruptedException {
        OrderState state = initialState;
        for (int i = 0; i < 20; i++) {
            Optional<Order> current = orderRepository.findById(orderId);
            if (current.isPresent()) {
                state = current.get().getState();
                if (state != initialState) {
                    return state;
                }
            }
            Thread.sleep(250);
        }
        return state;
    }

    // 8s, not 5s: order.status-changed/payment.refund.requested now go
    // through OutboxPublisher's poller (ADR-0034 Update/ADR-0037) instead
    // of a direct publish, so a row written just after a poll tick can
    // wait nearly a full 5s fixedDelay before the next tick sends it.
    private void assertProbeReceivedOrderStatusChanged(String orderId, String previousState, String newState) {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbitTemplate.receive(OrderStatusChangedProbeSupport.PROBE_QUEUE, 500);
            if (message == null) {
                continue;
            }
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (body.contains(orderId)) {
                assertThat(body).contains(previousState);
                assertThat(body).contains(newState);
                return;
            }
        }
        throw new AssertionError("expected a real order.status-changed publish for order " + orderId
                + " (" + previousState + " -> " + newState + ") but none arrived within the timeout");
    }

    private void assertProbeReceivedRefundPaymentCommand(String orderId) {
        if (!pollForRefundPaymentCommand(orderId, 8000)) {
            throw new AssertionError("expected a real RefundPaymentCommand publish for order " + orderId
                    + " but none arrived within the timeout");
        }
    }

    private void drainRefundPaymentCommand(String orderId) {
        pollForRefundPaymentCommand(orderId, 8000);
    }

    private boolean pollForRefundPaymentCommand(String orderId, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbitTemplate.receive(RefundPaymentRequestedProbeSupport.PROBE_QUEUE, 500);
            if (message == null) {
                continue;
            }
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (body.contains(orderId)) {
                return true;
            }
        }
        return false;
    }
}
