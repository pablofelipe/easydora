package com.easydora.billing.service;

import com.easydora.billing.entity.OutboxEvent;
import com.easydora.billing.messaging.events.PaymentEvent;
import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.OutboxEventRepository;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.service.provider.PaymentMockService;
import com.easydora.billing.service.provider.PaymentProvider;
import com.easydora.billing.support.OutboxEventCaptureSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private PaymentService newPaymentService(PaymentProvider provider) {
        return new PaymentService(paymentRepository, provider, outboxEventRepository,
                OutboxEventCaptureSupport.objectMapper(), new SimpleMeterRegistry());
    }

    private List<PaymentEvent> events(List<OutboxEvent> savedEvents) {
        return savedEvents.stream()
                .map(event -> OutboxEventCaptureSupport.bodyAs(event, PaymentEvent.class))
                .toList();
    }

    @Test
    void aPaymentInApprovedStatusIsRefundedAndPublishesPaymentRefunded() {
        Payment approved = new Payment("order-1", new BigDecimal("100.00"));
        approved.setStatus(PaymentStatus.APPROVED);
        approved.setTransactionId("TXN_original");
        when(paymentRepository.findByOrderId("order-1")).thenReturn(Optional.of(approved));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        PaymentService paymentService = newPaymentService(new PaymentMockService());

        paymentService.refundPayment("order-1");

        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        // The original charge's transactionId is never overwritten by the
        // refund -- no new refund-specific identifier is invented (ADR-0034).
        assertThat(approved.getTransactionId()).isEqualTo("TXN_original");

        List<PaymentEvent> events = events(savedEvents);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOrderId()).isEqualTo("order-1");
        assertThat(events.get(0).getFailureReason()).isNull();
    }

    @Test
    void refundingAnAlreadyRefundedPaymentIsIdempotentAndDoesNotRepublish() {
        Payment refunded = new Payment("order-2", new BigDecimal("50.00"));
        refunded.setStatus(PaymentStatus.REFUNDED);
        when(paymentRepository.findByOrderId("order-2")).thenReturn(Optional.of(refunded));

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        PaymentService paymentService = newPaymentService(new PaymentMockService());

        paymentService.refundPayment("order-2");

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        assertThat(savedEvents).isEmpty();
    }

    @Test
    void refundingAnOrderWithNoPaymentPublishesPaymentRefundFailed() {
        when(paymentRepository.findByOrderId("order-missing")).thenReturn(Optional.empty());

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        PaymentService paymentService = newPaymentService(new PaymentMockService());

        paymentService.refundPayment("order-missing");

        List<PaymentEvent> events = events(savedEvents);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFailureReason()).contains("order-missing");
    }

    @Test
    void refundingAPaymentNotInApprovedStatusPublishesPaymentRefundFailed() {
        Payment pending = new Payment("order-3", new BigDecimal("30.00"));
        when(paymentRepository.findByOrderId("order-3")).thenReturn(Optional.of(pending));

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        PaymentService paymentService = newPaymentService(new PaymentMockService());

        paymentService.refundPayment("order-3");

        List<PaymentEvent> events = events(savedEvents);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFailureReason()).contains("PENDING");
    }
}
