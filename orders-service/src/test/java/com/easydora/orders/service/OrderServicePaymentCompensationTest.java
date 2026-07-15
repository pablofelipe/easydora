package com.easydora.orders.service;

import com.easydora.orders.entity.Order;
import com.easydora.orders.entity.OutboxEvent;
import com.easydora.orders.event.OrderStatusChangedEvent;
import com.easydora.orders.event.RefundPaymentCommand;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-0034: a payment.approved that arrives while the order is already
 * INVENTORY_FAILED/CANCELLED is stray -- Billing captured money for an
 * order that can no longer be honored. OrderService.handlePaymentReceived
 * is the one place that detects this (its own PAYMENT_RECEIVED transition
 * is rejected by the state machine from either state) and is the one that
 * initiates compensation, publishing a RefundPaymentCommand -- never
 * touching Payment directly (Billing owns that).
 */
@ExtendWith(MockitoExtension.class)
class OrderServicePaymentCompensationTest {

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
                outboxEventRepository, OutboxEventCaptureSupport.objectMapper(), new SimpleMeterRegistry());
    }

    private Order orderIn(OrderState state) {
        Order order = new Order();
        order.setId("order-1");
        order.setTotalAmount(new BigDecimal("199.90"));
        order.setState(state);
        return order;
    }

    private <T> List<T> published(List<OutboxEvent> savedEvents, String routingKey, Class<T> type) {
        return savedEvents.stream()
                .filter(event -> event.getRoutingKey().equals(routingKey))
                .map(event -> OutboxEventCaptureSupport.bodyAs(event, type))
                .toList();
    }

    @Test
    void aStrayApprovalForAnInventoryFailedOrderInitiatesCompensation() {
        Order order = orderIn(OrderState.INVENTORY_FAILED);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // PAYMENT_RECEIVED is rejected from INVENTORY_FAILED -- exactly what
        // the real state machine already does.
        when(stateMachineService.sendEvent("order-1", OrderEvent.PAYMENT_RECEIVED)).thenReturn(false);
        when(stateMachineService.sendEvent("order-1", OrderEvent.INITIATE_REFUND)).thenAnswer(invocation -> {
            order.setState(OrderState.REFUNDING);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.REFUNDING);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        orderService.handlePaymentReceived("order-1");

        assertThat(order.getState()).isEqualTo(OrderState.REFUNDING);

        List<OrderStatusChangedEvent> statusEvents = published(savedEvents, "order.status-changed", OrderStatusChangedEvent.class);
        assertThat(statusEvents).hasSize(1);
        assertThat(statusEvents.get(0).getPreviousState()).isEqualTo(OrderState.INVENTORY_FAILED);
        assertThat(statusEvents.get(0).getNewState()).isEqualTo(OrderState.REFUNDING);

        List<RefundPaymentCommand> commands = published(savedEvents, "payment.refund.requested", RefundPaymentCommand.class);
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).getOrderId()).isEqualTo("order-1");
    }

    @Test
    void aStrayApprovalForACancelledOrderInitiatesCompensation() {
        Order order = orderIn(OrderState.CANCELLED);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent("order-1", OrderEvent.PAYMENT_RECEIVED)).thenReturn(false);
        when(stateMachineService.sendEvent("order-1", OrderEvent.INITIATE_REFUND)).thenAnswer(invocation -> {
            order.setState(OrderState.REFUNDING);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.REFUNDING);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        orderService.handlePaymentReceived("order-1");

        assertThat(order.getState()).isEqualTo(OrderState.REFUNDING);
        assertThat(published(savedEvents, "payment.refund.requested", RefundPaymentCommand.class)).hasSize(1);
    }

    @Test
    void aDuplicateApprovalForAnOrderAlreadyRefundingDoesNotReInitiateCompensation() {
        Order order = orderIn(OrderState.REFUNDING);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(stateMachineService.sendEvent("order-1", OrderEvent.PAYMENT_RECEIVED)).thenReturn(false);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        orderService.handlePaymentReceived("order-1");

        assertThat(order.getState()).isEqualTo(OrderState.REFUNDING);
        verify(stateMachineService, never()).sendEvent("order-1", OrderEvent.INITIATE_REFUND);
        assertThat(published(savedEvents, "payment.refund.requested", RefundPaymentCommand.class)).isEmpty();
        assertThat(published(savedEvents, "order.status-changed", OrderStatusChangedEvent.class)).isEmpty();
    }
}
