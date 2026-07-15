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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * shipOrder/deliverOrder activate the PAYMENT_APPROVED->SHIPPED->DELIVERED
 * transitions the state machine already had configured but nothing ever
 * triggered. Both reuse isTransitionAllowed (the same single source of
 * truth cancelOrder now uses) instead of a hand-written eligibility check,
 * and both reuse publishOrderStatusChanged/order.status-changed rather
 * than a new event type.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceShipDeliverTest {

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

    @Test
    void shipOrder_whenPaymentApproved_transitionsToShippedAndPublishesStatusChanged() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.PAYMENT_APPROVED);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.isTransitionAllowed(OrderState.PAYMENT_APPROVED, OrderEvent.SHIP_ORDER))
                .thenReturn(true);
        when(stateMachineService.sendEvent("order-1", OrderEvent.SHIP_ORDER)).thenReturn(true);
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.SHIPPED);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        orderService.shipOrder("order-1");

        assertThat(savedEvents).extracting(OutboxEvent::getRoutingKey).contains("order.status-changed");
    }

    @Test
    void shipOrder_whenNotPaymentApproved_throwsWithoutSendingAnyEvent() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.PENDING);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(stateMachineService.isTransitionAllowed(OrderState.PENDING, OrderEvent.SHIP_ORDER))
                .thenReturn(false);

        OrderService orderService = newOrderService();

        assertThatThrownBy(() -> orderService.shipOrder("order-1"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shipOrder_whenOrderDoesNotExist_throws() {
        when(orderRepository.findById("missing")).thenReturn(Optional.empty());

        OrderService orderService = newOrderService();

        assertThatThrownBy(() -> orderService.shipOrder("missing"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void deliverOrder_whenShippedAndOwnedByBuyer_transitionsToDeliveredAndPublishesStatusChanged() {
        Buyer buyer = new Buyer();
        buyer.setUserId(42L);
        buyer.setActive(true);
        when(buyerRepository.findById(42L)).thenReturn(Optional.of(buyer));

        Order order = new Order();
        order.setId("order-1");
        order.setUserId(42L);
        order.setState(OrderState.SHIPPED);
        when(orderRepository.findByIdAndUserId("order-1", 42L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.isTransitionAllowed(OrderState.SHIPPED, OrderEvent.DELIVER_ORDER))
                .thenReturn(true);
        when(stateMachineService.sendEvent("order-1", OrderEvent.DELIVER_ORDER)).thenReturn(true);
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.DELIVERED);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        orderService.deliverOrder("order-1", 42L);

        assertThat(savedEvents).extracting(OutboxEvent::getRoutingKey).contains("order.status-changed");
    }

    @Test
    void deliverOrder_whenNotShipped_throwsWithoutSendingAnyEvent() {
        Buyer buyer = new Buyer();
        buyer.setUserId(42L);
        buyer.setActive(true);
        when(buyerRepository.findById(42L)).thenReturn(Optional.of(buyer));

        Order order = new Order();
        order.setId("order-1");
        order.setUserId(42L);
        order.setState(OrderState.PAYMENT_APPROVED);
        when(orderRepository.findByIdAndUserId("order-1", 42L)).thenReturn(Optional.of(order));
        when(stateMachineService.isTransitionAllowed(OrderState.PAYMENT_APPROVED, OrderEvent.DELIVER_ORDER))
                .thenReturn(false);

        OrderService orderService = newOrderService();

        assertThatThrownBy(() -> orderService.deliverOrder("order-1", 42L))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void deliverOrder_whenOrderNotOwnedByBuyer_throws() {
        Buyer buyer = new Buyer();
        buyer.setUserId(42L);
        buyer.setActive(true);
        when(buyerRepository.findById(42L)).thenReturn(Optional.of(buyer));
        when(orderRepository.findByIdAndUserId("order-1", 42L)).thenReturn(Optional.empty());

        OrderService orderService = newOrderService();

        assertThatThrownBy(() -> orderService.deliverOrder("order-1", 42L))
                .isInstanceOf(RuntimeException.class);
    }
}
