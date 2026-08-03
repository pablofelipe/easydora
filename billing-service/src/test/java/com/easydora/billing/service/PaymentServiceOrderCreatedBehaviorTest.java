package com.easydora.billing.service;

import com.easydora.billing.messaging.events.OrderCreatedEvent;
import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.OutboxEventRepository;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.service.provider.PaymentProvider;
import com.easydora.billing.support.OutboxEventCaptureSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavior contract for the orders -> billing hop (ADR-0007): receiving an
 * order-created event must create a pending payment, exactly once per
 * order. Neither Kafka nor RabbitMQ is referenced anywhere in this test —
 * PaymentService.createPendingPayment is already broker-agnostic today (it
 * takes a plain OrderCreatedEvent), unlike every other hop in this project,
 * so this test passes now. It documents the reaction this hop's future
 * RabbitMQ listener must trigger, and guards against regressions to it
 * independent of whichever consumer class ends up calling it.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceOrderCreatedBehaviorTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private PaymentProvider paymentProvider;

    @Test
    void orderCreatedEventCreatesAPendingPayment() {
        when(paymentRepository.findByOrderId("order-123")).thenReturn(Optional.empty());

        PaymentService paymentService = new PaymentService(paymentRepository, paymentProvider, outboxEventRepository,
                OutboxEventCaptureSupport.objectMapper(), new SimpleMeterRegistry(), io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);

        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId("order-123");
        event.setUserId(42L);
        event.setTotalAmount(new BigDecimal("99.90"));

        paymentService.createPendingPayment(event);

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment savedPayment = captor.getValue();
        assertThat(savedPayment.getOrderId()).isEqualTo("order-123");
        assertThat(savedPayment.getUserId()).isEqualTo(42L);
        assertThat(savedPayment.getAmount()).isEqualByComparingTo("99.90");
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void orderCreatedEventIsIgnoredWhenAPaymentAlreadyExistsForTheOrder() {
        when(paymentRepository.findByOrderId("order-123")).thenReturn(Optional.of(new Payment()));

        PaymentService paymentService = new PaymentService(paymentRepository, paymentProvider, outboxEventRepository,
                OutboxEventCaptureSupport.objectMapper(), new SimpleMeterRegistry(), io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);

        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId("order-123");
        event.setUserId(42L);
        event.setTotalAmount(new BigDecimal("99.90"));

        paymentService.createPendingPayment(event);

        verify(paymentRepository, never()).save(any(Payment.class));
    }
}
