package com.easydora.orders.service;

import com.easydora.orders.entity.Order;
import com.easydora.orders.entity.OutboxEvent;
import com.easydora.orders.event.OrderStatusChangedEvent;
import com.easydora.orders.repository.BuyerRepository;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.repository.OutboxEventRepository;
import com.easydora.orders.repository.ProductOwnershipRepository;
import com.easydora.orders.statemachine.OrderEvent;
import com.easydora.orders.statemachine.OrderState;
import com.easydora.orders.support.OutboxEventCaptureSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-0034: closing the loop on the other end of the compensation
 * round-trip -- once Billing resolves a RefundPaymentCommand, orders-service
 * reacts to payment.refunded/payment.refund.failed the same way it reacts
 * to every other outcome event (saveAndFlush + publishOrderStatusChanged,
 * ADR-0033's pattern), and is a no-op (guarded by the state machine itself)
 * for a redelivered/duplicate outcome once the order already left
 * REFUNDING.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceRefundOutcomeTest {

    @Mock
    private BuyerRepository buyerRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderStateMachineService stateMachineService;
    @Mock
    private ProductOwnershipRepository productOwnershipRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OrderService newOrderService() {
        return new OrderService(buyerRepository, orderRepository, stateMachineService, productOwnershipRepository,
                outboxEventRepository, OutboxEventCaptureSupport.objectMapper(), new SimpleMeterRegistry(), io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);
    }

    private List<OrderStatusChangedEvent> statusChangedEvents(List<OutboxEvent> savedEvents) {
        return savedEvents.stream()
                .filter(event -> event.getRoutingKey().equals("order.status-changed"))
                .map(event -> OutboxEventCaptureSupport.bodyAs(event, OrderStatusChangedEvent.class))
                .toList();
    }

    @Test
    void handleRefundCompleted_movesRefundingToRefundedAndPublishes() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.REFUNDING);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent("order-1", OrderEvent.REFUND_COMPLETED)).thenAnswer(invocation -> {
            order.setState(OrderState.REFUNDED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.REFUNDED);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        orderService.handleRefundCompleted("order-1");

        assertThat(order.getState()).isEqualTo(OrderState.REFUNDED);
        List<OrderStatusChangedEvent> events = statusChangedEvents(savedEvents);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getPreviousState()).isEqualTo(OrderState.REFUNDING);
        assertThat(events.get(0).getNewState()).isEqualTo(OrderState.REFUNDED);
    }

    @Test
    void handleRefundFailed_movesRefundingToRefundFailedAndPublishes() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.REFUNDING);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent("order-1", OrderEvent.REFUND_FAILED)).thenAnswer(invocation -> {
            order.setState(OrderState.REFUND_FAILED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.REFUND_FAILED);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        orderService.handleRefundFailed("order-1", "Payment not found for order order-1");

        assertThat(order.getState()).isEqualTo(OrderState.REFUND_FAILED);
        assertThat(order.getRefundFailureReason())
                .withFailMessage("the failure reason should be persisted, not only logged, for the admin remediation queue")
                .isEqualTo("Payment not found for order order-1");
        List<OrderStatusChangedEvent> events = statusChangedEvents(savedEvents);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getPreviousState()).isEqualTo(OrderState.REFUNDING);
        assertThat(events.get(0).getNewState()).isEqualTo(OrderState.REFUND_FAILED);
    }

    @Test
    void handleRefundCompleted_isANoOpForAnOrderThatAlreadyLeftRefunding() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.REFUNDED);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(stateMachineService.sendEvent("order-1", OrderEvent.REFUND_COMPLETED)).thenReturn(false);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        orderService.handleRefundCompleted("order-1");

        verify(orderRepository, never()).saveAndFlush(any(Order.class));
        assertThat(statusChangedEvents(savedEvents)).isEmpty();
    }
}
