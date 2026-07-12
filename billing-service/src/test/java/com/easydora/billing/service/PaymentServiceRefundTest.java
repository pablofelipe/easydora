package com.easydora.billing.service;

import com.easydora.billing.messaging.events.PaymentEvent;
import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.service.provider.PaymentMockService;
import com.easydora.billing.service.provider.PaymentProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-0034: PaymentService.refundPayment reacting to a RefundPaymentCommand.
 * Billing decides everything here -- these tests never assume Orders'
 * side of the flow. PaymentMockService is used for real (not mocked) in
 * the success test, same precedent as PaymentServiceDeterministicApprovalTest:
 * the property under test includes the actual wiring, not just a stub.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceRefundTest {

    private static class RecordingRabbitTemplate extends RabbitTemplate {
        final List<Object> payloads = new ArrayList<>();

        RecordingRabbitTemplate() {
            super(mock(ConnectionFactory.class));
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object) {
            payloads.add(object);
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object, MessagePostProcessor messagePostProcessor) {
            payloads.add(object);
        }

        List<PaymentEvent> events() {
            return payloads.stream()
                    .filter(PaymentEvent.class::isInstance)
                    .map(PaymentEvent.class::cast)
                    .toList();
        }
    }

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentService newPaymentService(RabbitTemplate rabbitTemplate, PaymentProvider provider) {
        return new PaymentService(paymentRepository, rabbitTemplate, provider);
    }

    @Test
    void aPaymentInApprovedStatusIsRefundedAndPublishesPaymentRefunded() {
        Payment approved = new Payment("order-1", new BigDecimal("100.00"));
        approved.setStatus(PaymentStatus.APPROVED);
        approved.setTransactionId("TXN_original");
        when(paymentRepository.findByOrderId("order-1")).thenReturn(Optional.of(approved));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        PaymentService paymentService = newPaymentService(rabbitTemplate, new PaymentMockService());

        paymentService.refundPayment("order-1");

        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        // The original charge's transactionId is never overwritten by the
        // refund -- no new refund-specific identifier is invented (ADR-0034).
        assertThat(approved.getTransactionId()).isEqualTo("TXN_original");

        List<PaymentEvent> events = rabbitTemplate.events();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOrderId()).isEqualTo("order-1");
        assertThat(events.get(0).getFailureReason()).isNull();
    }

    @Test
    void refundingAnAlreadyRefundedPaymentIsIdempotentAndDoesNotRepublish() {
        Payment refunded = new Payment("order-2", new BigDecimal("50.00"));
        refunded.setStatus(PaymentStatus.REFUNDED);
        when(paymentRepository.findByOrderId("order-2")).thenReturn(Optional.of(refunded));

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        PaymentService paymentService = newPaymentService(rabbitTemplate, new PaymentMockService());

        paymentService.refundPayment("order-2");

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        assertThat(rabbitTemplate.events()).isEmpty();
    }

    @Test
    void refundingAnOrderWithNoPaymentPublishesPaymentRefundFailed() {
        when(paymentRepository.findByOrderId("order-missing")).thenReturn(Optional.empty());

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        PaymentService paymentService = newPaymentService(rabbitTemplate, new PaymentMockService());

        paymentService.refundPayment("order-missing");

        List<PaymentEvent> events = rabbitTemplate.events();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFailureReason()).contains("order-missing");
    }

    @Test
    void refundingAPaymentNotInApprovedStatusPublishesPaymentRefundFailed() {
        Payment pending = new Payment("order-3", new BigDecimal("30.00"));
        when(paymentRepository.findByOrderId("order-3")).thenReturn(Optional.of(pending));

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        PaymentService paymentService = newPaymentService(rabbitTemplate, new PaymentMockService());

        paymentService.refundPayment("order-3");

        List<PaymentEvent> events = rabbitTemplate.events();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFailureReason()).contains("PENDING");
    }
}
