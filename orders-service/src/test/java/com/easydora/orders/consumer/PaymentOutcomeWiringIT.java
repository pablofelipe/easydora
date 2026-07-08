package com.easydora.orders.consumer;

import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.entity.Order;
import com.easydora.orders.event.PaymentEvent;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.statemachine.OrderState;
import com.easydora.orders.support.OrderStatusChangedProbeSupport;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives billing-service's payment outcome events (payment.approved /
 * payment.failed) through the real order.exchange and asserts
 * PaymentEventsConsumer applies the outcome to the order's real state
 * machine, persists it to a real Postgres, and publishes a real
 * order.status-changed in the process (CI Phase 2 service containers).
 * billing-service itself is not involved -- this test publishes the same
 * event shape its PaymentService.publishPaymentEvent would, to prove
 * orders-service's consumer side of the handshake independently, mirroring
 * StockOutcomeWiringIT's shape for the equivalent inventory-outcome hop.
 *
 * {@code @DirtiesContext} follows the same precedent as StockOutcomeWiringIT.
 */
@SpringBootTest
@DirtiesContext
class PaymentOutcomeWiringIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void paymentApprovedEventMovesOrderToPaymentApproved() throws Exception {
        String orderId = "it-" + UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(1L);
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setState(OrderState.INVENTORY_RESERVED);
        orderRepository.save(order);

        PaymentEvent event = new PaymentEvent();
        event.setOrderId(orderId);
        event.setTransactionId("txn-" + UUID.randomUUID());
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.PAYMENT_APPROVED_ROUTING_KEY, event);

        OrderState finalState = awaitState(orderId);
        assertThat(finalState)
                .withFailMessage("order %s should have transitioned to PAYMENT_APPROVED after a real payment.approved publish", orderId)
                .isEqualTo(OrderState.PAYMENT_APPROVED);

        assertOrderStatusChangedWasPublished(orderId, "INVENTORY_RESERVED", "PAYMENT_APPROVED");
    }

    @Test
    void paymentFailedEventMovesOrderToPaymentFailed() throws Exception {
        String orderId = "it-" + UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(1L);
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setState(OrderState.INVENTORY_RESERVED);
        orderRepository.save(order);

        PaymentEvent event = new PaymentEvent();
        event.setOrderId(orderId);
        event.setTransactionId("txn-" + UUID.randomUUID());
        event.setFailureReason("Payment declined by the processor");
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.PAYMENT_FAILED_ROUTING_KEY, event);

        OrderState finalState = awaitState(orderId);
        assertThat(finalState)
                .withFailMessage("order %s should have transitioned to PAYMENT_FAILED after a real payment.failed publish", orderId)
                .isEqualTo(OrderState.PAYMENT_FAILED);

        assertOrderStatusChangedWasPublished(orderId, "INVENTORY_RESERVED", "PAYMENT_FAILED");
    }

    private OrderState awaitState(String orderId) throws InterruptedException {
        OrderState state = OrderState.INVENTORY_RESERVED;
        for (int i = 0; i < 20; i++) {
            Optional<Order> current = orderRepository.findById(orderId);
            if (current.isPresent()) {
                state = current.get().getState();
                if (state != OrderState.INVENTORY_RESERVED) {
                    return state;
                }
            }
            Thread.sleep(250);
        }
        return state;
    }

    /**
     * The probe queue is durable and shared for the whole test JVM, so it
     * also accumulates order.status-changed messages published as a side
     * effect of other *IT classes (e.g. StockOutcomeWiringIT's own
     * handleInventoryReserved/handleInventoryFailed calls). Scans for the
     * message matching this test's own orderId instead of assuming the
     * next message off the queue is it -- unrelated messages are simply
     * drained and discarded, harmlessly, since nothing else reads this
     * queue.
     */
    private void assertOrderStatusChangedWasPublished(String orderId, String previousState, String newState) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbitTemplate.receive(OrderStatusChangedProbeSupport.PROBE_QUEUE, 500);
            if (message == null) {
                continue;
            }
            String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            if (body.contains(orderId)) {
                assertThat(body).contains(previousState);
                assertThat(body).contains(newState);
                return;
            }
        }
        throw new AssertionError("expected a real order.status-changed publish for order " + orderId
                + " but none arrived on the probe queue within the timeout");
    }
}
