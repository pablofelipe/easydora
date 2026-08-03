package com.easydora.billing.service;

import com.easydora.billing.exception.OrderNotReadyForPaymentException;
import com.easydora.billing.model.Payment;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-0026 documented processPayment as callable "at any time" -- any
 * direct caller (Postman, e2e-tests, a future client) could approve a
 * charge for an order still PROCESSING or already CANCELLED, relying
 * entirely on ADR-0034's compensation saga to unwind the mess afterwards.
 * This guard closes the avoidable case at the source: processPayment now
 * rejects an order whose last known state (kept current via
 * OrderEventListener.handleOrderStatusChanged, reacting to the real
 * order.status-changed broadcast) isn't INVENTORY_RESERVED. The
 * compensation saga still exists for genuine races this guard can't see
 * (e.g. a stray payment.approved for an order that moved on between this
 * check and the provider call) -- this doesn't replace it, it just
 * shrinks how often it's needed.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceOrderStateGuardTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private final PaymentProvider paymentProvider = new PaymentMockService();

    private PaymentService newPaymentService() {
        return new PaymentService(paymentRepository, paymentProvider, outboxEventRepository,
                OutboxEventCaptureSupport.objectMapper(), new SimpleMeterRegistry(), io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);
    }

    @Test
    void processPaymentRejectsAnOrderNotYetInventoryReserved() {
        Payment payment = new Payment("order-too-early", new BigDecimal("100.00"));
        payment.setOrderState("PROCESSING");
        when(paymentRepository.findByOrderId("order-too-early")).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> newPaymentService().processPayment("order-too-early"))
                .isInstanceOf(OrderNotReadyForPaymentException.class);

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    @Test
    void processPaymentRejectsAnOrderWithNoKnownStateYet() {
        Payment payment = new Payment("order-unknown-state", new BigDecimal("100.00"));
        // orderState left null -- no order.status-changed has arrived yet.
        when(paymentRepository.findByOrderId("order-unknown-state")).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> newPaymentService().processPayment("order-unknown-state"))
                .isInstanceOf(OrderNotReadyForPaymentException.class);
    }

    @Test
    void processPaymentAcceptsAnOrderThatIsInventoryReserved() {
        Payment payment = new Payment("order-ready", new BigDecimal("100.00"));
        payment.setOrderState("INVENTORY_RESERVED");
        when(paymentRepository.findByOrderId("order-ready")).thenReturn(Optional.of(payment));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = newPaymentService().processPayment("order-ready");

        assertThat(result.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void anAlreadyApprovedPaymentShortCircuitsBeforeTheStateGuard() {
        Payment payment = new Payment("order-already-approved", new BigDecimal("100.00"));
        payment.setOrderState("PAYMENT_APPROVED");
        payment.setStatus(com.easydora.billing.model.PaymentStatus.APPROVED);
        when(paymentRepository.findByOrderId("order-already-approved")).thenReturn(Optional.of(payment));

        // Must not throw: an already-APPROVED payment is returned as-is,
        // the same idempotent short-circuit processPayment already had
        // before this guard existed -- a duplicate/replayed call must
        // stay a no-op, not suddenly start failing because the order has
        // since moved past INVENTORY_RESERVED.
        var result = newPaymentService().processPayment("order-already-approved");

        assertThat(result.getStatus()).isEqualTo("APPROVED");
    }
}
