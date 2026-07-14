package com.easydora.billing.service;

import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.billing.messaging.events.PaymentEvent;
import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.service.provider.PaymentProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Behavior contract for the billing -> orders hop:
 * once a payment resolves to APPROVED or FAILED, publishPaymentEvent must
 * put the right event on order.exchange with the right routing key -- the
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
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
    @Mock
    private PaymentProvider paymentProvider;

    @Test
    void approvedPaymentPublishesPaymentApprovedEvent() {
        PaymentService paymentService = new PaymentService(paymentRepository, rabbitTemplate, paymentProvider, new SimpleMeterRegistry());

        Payment payment = new Payment("order-123", new BigDecimal("99.90"));
        payment.setTransactionId("txn-1");
        payment.setStatus(PaymentStatus.APPROVED);

        paymentService.publishPaymentEvent(payment);

        ArgumentCaptor<PaymentEvent> captor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.ORDER_EXCHANGE), eq(RabbitMQConfig.PAYMENT_APPROVED_KEY), captor.capture(), any(MessagePostProcessor.class));
        assertThat(captor.getValue().getOrderId()).isEqualTo("order-123");
        assertThat(captor.getValue().getTransactionId()).isEqualTo("txn-1");
        assertThat(captor.getValue().getFailureReason()).isNull();
    }

    @Test
    void failedPaymentPublishesPaymentFailedEvent() {
        PaymentService paymentService = new PaymentService(paymentRepository, rabbitTemplate, paymentProvider, new SimpleMeterRegistry());

        Payment payment = new Payment("order-456", new BigDecimal("49.90"));
        payment.setTransactionId("txn-2");
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason("Payment declined by the processor");

        paymentService.publishPaymentEvent(payment);

        ArgumentCaptor<PaymentEvent> captor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.ORDER_EXCHANGE), eq(RabbitMQConfig.PAYMENT_FAILED_KEY), captor.capture(), any(MessagePostProcessor.class));
        assertThat(captor.getValue().getOrderId()).isEqualTo("order-456");
        assertThat(captor.getValue().getFailureReason()).isEqualTo("Payment declined by the processor");
    }

    @Test
    void pendingPaymentPublishesNoEvent() {
        PaymentService paymentService = new PaymentService(paymentRepository, rabbitTemplate, paymentProvider, new SimpleMeterRegistry());

        Payment payment = new Payment("order-789", new BigDecimal("10.00"));

        paymentService.publishPaymentEvent(payment);

        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }
}
