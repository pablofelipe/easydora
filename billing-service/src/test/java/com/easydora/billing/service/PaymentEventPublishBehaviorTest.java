package com.easydora.billing.service;

import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.billing.entity.OutboxEvent;
import com.easydora.billing.messaging.events.PaymentEvent;
import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.OutboxEventRepository;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.service.provider.PaymentProvider;
import com.easydora.billing.support.OutboxEventCaptureSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior contract for the billing -> orders hop:
 * once a payment resolves to APPROVED or FAILED, publishPaymentEvent must
 * record the right event in the outbox with the right routing key -- the
 * missing link ADR-0001 (finding 5) left unreachable after removing the
 * previous, incorrectly-typed PaymentEventProducer. Doesn't exercise
 * processPayment's approval decision (see PaymentServiceDeterministicApprovalTest
 * for that) -- calls publishPaymentEvent directly with a Payment already in
 * the state under test instead. paymentProvider is unused by these tests
 * (never invoked), only required to satisfy the constructor.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventPublishBehaviorTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private PaymentProvider paymentProvider;

    private PaymentService newPaymentService() {
        return new PaymentService(paymentRepository, paymentProvider, outboxEventRepository,
                OutboxEventCaptureSupport.objectMapper(), new SimpleMeterRegistry());
    }

    @Test
    void approvedPaymentRecordsPaymentApprovedEventInTheOutbox() {
        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        PaymentService paymentService = newPaymentService();

        Payment payment = new Payment("order-123", new BigDecimal("99.90"));
        payment.setTransactionId("txn-1");
        payment.setStatus(PaymentStatus.APPROVED);

        paymentService.publishPaymentEvent(payment);

        assertThat(savedEvents).hasSize(1);
        OutboxEvent saved = savedEvents.get(0);
        assertThat(saved.getExchange()).isEqualTo(RabbitMQConfig.ORDER_EXCHANGE);
        assertThat(saved.getRoutingKey()).isEqualTo(RabbitMQConfig.PAYMENT_APPROVED_KEY);

        PaymentEvent event = OutboxEventCaptureSupport.bodyAs(saved, PaymentEvent.class);
        assertThat(event.getOrderId()).isEqualTo("order-123");
        assertThat(event.getTransactionId()).isEqualTo("txn-1");
        assertThat(event.getFailureReason()).isNull();
    }

    @Test
    void failedPaymentRecordsPaymentFailedEventInTheOutbox() {
        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        PaymentService paymentService = newPaymentService();

        Payment payment = new Payment("order-456", new BigDecimal("49.90"));
        payment.setTransactionId("txn-2");
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason("Payment declined by the processor");

        paymentService.publishPaymentEvent(payment);

        assertThat(savedEvents).hasSize(1);
        OutboxEvent saved = savedEvents.get(0);
        assertThat(saved.getExchange()).isEqualTo(RabbitMQConfig.ORDER_EXCHANGE);
        assertThat(saved.getRoutingKey()).isEqualTo(RabbitMQConfig.PAYMENT_FAILED_KEY);

        PaymentEvent event = OutboxEventCaptureSupport.bodyAs(saved, PaymentEvent.class);
        assertThat(event.getOrderId()).isEqualTo("order-456");
        assertThat(event.getFailureReason()).isEqualTo("Payment declined by the processor");
    }

    @Test
    void pendingPaymentRecordsNoEventInTheOutbox() {
        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        PaymentService paymentService = newPaymentService();

        Payment payment = new Payment("order-789", new BigDecimal("10.00"));

        paymentService.publishPaymentEvent(payment);

        assertThat(savedEvents).isEmpty();
    }
}
