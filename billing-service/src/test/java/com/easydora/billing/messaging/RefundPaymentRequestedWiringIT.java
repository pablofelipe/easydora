package com.easydora.billing.messaging;

import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.billing.messaging.events.RefundPaymentCommand;
import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.support.RefundOutcomeProbeSupport;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0034, against real Postgres/RabbitMQ (CI Phase 2 service containers):
 * publishes a real RefundPaymentCommand onto order.exchange (the same shape
 * orders-service's OrderService.initiateRefundCompensation would) and
 * asserts OrderEventListener/PaymentService.refundPayment actually persists
 * the refund and publishes a real outcome. orders-service itself is not
 * involved, mirroring OrderCreatedWiringIT's precedent of proving each side
 * of a handshake independently.
 */
@SpringBootTest
class RefundPaymentRequestedWiringIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void refundingAnApprovedPaymentPersistsRefundedAndPublishesPaymentRefunded() throws Exception {
        String orderId = "it-" + UUID.randomUUID();
        Payment payment = new Payment(orderId, new BigDecimal("50.00"));
        payment.setUserId(1L);
        payment.setStatus(PaymentStatus.APPROVED);
        payment.setTransactionId("TXN_" + UUID.randomUUID());
        paymentRepository.saveAndFlush(payment);

        publishRefundRequested(orderId);

        Payment refunded = awaitStatus(orderId, PaymentStatus.APPROVED);
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        assertProbeReceived(RefundOutcomeProbeSupport.PAYMENT_REFUNDED_PROBE_QUEUE, orderId);
    }

    @Test
    void refundingAnOrderWithNoPaymentPublishesPaymentRefundFailed() throws Exception {
        String orderId = "it-missing-" + UUID.randomUUID();

        publishRefundRequested(orderId);

        assertProbeReceived(RefundOutcomeProbeSupport.PAYMENT_REFUND_FAILED_PROBE_QUEUE, orderId);
    }

    @Test
    void aRedeliveredRefundRequestForAnAlreadyRefundedPaymentIsIdempotent() throws Exception {
        String orderId = "it-" + UUID.randomUUID();
        Payment payment = new Payment(orderId, new BigDecimal("20.00"));
        payment.setUserId(1L);
        payment.setStatus(PaymentStatus.APPROVED);
        paymentRepository.saveAndFlush(payment);

        publishRefundRequested(orderId);
        awaitStatus(orderId, PaymentStatus.APPROVED);
        drainProbe(RefundOutcomeProbeSupport.PAYMENT_REFUNDED_PROBE_QUEUE, orderId);

        // Redelivered/duplicate command for the same order, now already
        // REFUNDED -- must be a no-op: no second publish.
        publishRefundRequested(orderId);
        Thread.sleep(1000);

        Payment stillRefunded = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(stillRefunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(pollProbe(RefundOutcomeProbeSupport.PAYMENT_REFUNDED_PROBE_QUEUE, orderId, 1500))
                .withFailMessage("a duplicate RefundPaymentCommand must not publish a second payment.refunded")
                .isFalse();
    }

    private void publishRefundRequested(String orderId) {
        RefundPaymentCommand command = new RefundPaymentCommand();
        command.setOrderId(orderId);
        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.REFUND_PAYMENT_REQUESTED_KEY, command);
    }

    private Payment awaitStatus(String orderId, PaymentStatus initialStatus) throws InterruptedException {
        Payment payment = null;
        for (int i = 0; i < 20; i++) {
            Optional<Payment> current = paymentRepository.findByOrderId(orderId);
            if (current.isPresent()) {
                payment = current.get();
                if (payment.getStatus() != initialStatus) {
                    return payment;
                }
            }
            Thread.sleep(250);
        }
        return payment;
    }

    private void assertProbeReceived(String queue, String orderId) {
        if (!pollProbe(queue, orderId, 5000)) {
            throw new AssertionError("expected a real publish on " + queue + " for order " + orderId
                    + " but none arrived within the timeout");
        }
    }

    private void drainProbe(String queue, String orderId) {
        pollProbe(queue, orderId, 5000);
    }

    private boolean pollProbe(String queue, String orderId, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbitTemplate.receive(queue, 500);
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
