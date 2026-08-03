package com.easydora.orders.service;

import com.easydora.orders.entity.Buyer;
import com.easydora.orders.entity.Order;
import com.easydora.orders.entity.OutboxEvent;
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
import org.springframework.dao.OptimisticLockingFailureException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A concurrent write on the same Order (ADR-0033's @Version) must surface
 * to the caller as a genuine conflict -- never be swallowed into a generic
 * "event not accepted" business error, and never let an order.status-changed
 * event escape for a write that never actually committed. Each scenario here
 * mirrors a case ADR-0033 lists as validated: concurrent cancellation,
 * payment, shipping, and delivery.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceOptimisticLockingTest {

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

    @Test
    void cancelOrder_propagatesAConcurrentConflictWithoutPublishingAnyEvent() {
        Buyer buyer = new Buyer();
        buyer.setUserId(1L);
        buyer.setActive(true);
        when(buyerRepository.findById(1L)).thenReturn(Optional.of(buyer));

        Order order = new Order();
        order.setId("order-1");
        order.setUserId(1L);
        order.setState(OrderState.PENDING);
        when(orderRepository.findByIdAndUserId("order-1", 1L)).thenReturn(Optional.of(order));
        when(stateMachineService.isTransitionAllowed(OrderState.PENDING, OrderEvent.CANCEL_ORDER)).thenReturn(true);
        when(stateMachineService.sendEvent("order-1", OrderEvent.CANCEL_ORDER)).thenAnswer(invocation -> {
            order.setState(OrderState.CANCELLED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.CANCELLED);
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenThrow(new OptimisticLockingFailureException("stale order-1"));

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        assertThatThrownBy(() -> orderService.cancelOrder("order-1", 1L))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(savedEvents)
                .withFailMessage("no event may be published for a write that lost the version conflict")
                .isEmpty();
    }

    @Test
    void shipOrder_propagatesAConcurrentConflictWithoutPublishingAnyEvent() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.PAYMENT_APPROVED);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(stateMachineService.isTransitionAllowed(OrderState.PAYMENT_APPROVED, OrderEvent.SHIP_ORDER)).thenReturn(true);
        when(stateMachineService.sendEvent("order-1", OrderEvent.SHIP_ORDER)).thenAnswer(invocation -> {
            order.setState(OrderState.SHIPPED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.SHIPPED);
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenThrow(new OptimisticLockingFailureException("stale order-1"));

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        assertThatThrownBy(() -> orderService.shipOrder("order-1"))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(savedEvents).isEmpty();
    }

    @Test
    void deliverOrder_propagatesAConcurrentConflictWithoutPublishingAnyEvent() {
        Buyer buyer = new Buyer();
        buyer.setUserId(42L);
        buyer.setActive(true);
        when(buyerRepository.findById(42L)).thenReturn(Optional.of(buyer));

        Order order = new Order();
        order.setId("order-1");
        order.setUserId(42L);
        order.setState(OrderState.SHIPPED);
        when(orderRepository.findByIdAndUserId("order-1", 42L)).thenReturn(Optional.of(order));
        when(stateMachineService.isTransitionAllowed(OrderState.SHIPPED, OrderEvent.DELIVER_ORDER)).thenReturn(true);
        when(stateMachineService.sendEvent("order-1", OrderEvent.DELIVER_ORDER)).thenAnswer(invocation -> {
            order.setState(OrderState.DELIVERED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.DELIVERED);
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenThrow(new OptimisticLockingFailureException("stale order-1"));

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        assertThatThrownBy(() -> orderService.deliverOrder("order-1", 42L))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(savedEvents).isEmpty();
    }

    @Test
    void handlePaymentReceived_propagatesAConcurrentConflictWithoutPublishingAnyEvent() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.INVENTORY_RESERVED);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(stateMachineService.sendEvent("order-1", OrderEvent.PAYMENT_RECEIVED)).thenAnswer(invocation -> {
            order.setState(OrderState.PAYMENT_APPROVED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.PAYMENT_APPROVED);
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenThrow(new OptimisticLockingFailureException("stale order-1"));

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        assertThatThrownBy(() -> orderService.handlePaymentReceived("order-1"))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(savedEvents).isEmpty();
    }
}
