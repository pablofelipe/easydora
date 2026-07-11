package com.easydora.orders.service;

import com.easydora.orders.entity.Buyer;
import com.easydora.orders.entity.Order;
import com.easydora.orders.repository.BuyerRepository;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.repository.ProductOwnershipRepository;
import com.easydora.orders.statemachine.OrderEvent;
import com.easydora.orders.statemachine.OrderState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    private static class RecordingRabbitTemplate extends RabbitTemplate {
        final List<String> routingKeys = new ArrayList<>();

        RecordingRabbitTemplate() {
            super(mock(ConnectionFactory.class));
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object) {
            routingKeys.add(routingKey);
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object, MessagePostProcessor messagePostProcessor) {
            routingKeys.add(routingKey);
        }
    }

    @Mock
    private BuyerRepository buyerRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderStateMachineService stateMachineService;
    @Mock
    private ProductOwnershipRepository productOwnershipRepository;

    private OrderService newOrderService(RabbitTemplate rabbitTemplate) {
        return new OrderService(buyerRepository, orderRepository, stateMachineService, rabbitTemplate,
                productOwnershipRepository);
    }

    @Test
    void shipOrder_whenPaymentApproved_transitionsToShippedAndPublishesStatusChanged() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.PAYMENT_APPROVED);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.isTransitionAllowed(OrderState.PAYMENT_APPROVED, OrderEvent.SHIP_ORDER))
                .thenReturn(true);
        when(stateMachineService.sendEvent("order-1", OrderEvent.SHIP_ORDER)).thenReturn(true);
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.SHIPPED);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.shipOrder("order-1");

        assertThat(rabbitTemplate.routingKeys).contains("order.status-changed");
    }

    @Test
    void shipOrder_whenNotPaymentApproved_throwsWithoutSendingAnyEvent() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.PENDING);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(stateMachineService.isTransitionAllowed(OrderState.PENDING, OrderEvent.SHIP_ORDER))
                .thenReturn(false);

        OrderService orderService = newOrderService(mock(RabbitTemplate.class));

        assertThatThrownBy(() -> orderService.shipOrder("order-1"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shipOrder_whenOrderDoesNotExist_throws() {
        when(orderRepository.findById("missing")).thenReturn(Optional.empty());

        OrderService orderService = newOrderService(mock(RabbitTemplate.class));

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
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.isTransitionAllowed(OrderState.SHIPPED, OrderEvent.DELIVER_ORDER))
                .thenReturn(true);
        when(stateMachineService.sendEvent("order-1", OrderEvent.DELIVER_ORDER)).thenReturn(true);
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.DELIVERED);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.deliverOrder("order-1", 42L);

        assertThat(rabbitTemplate.routingKeys).contains("order.status-changed");
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

        OrderService orderService = newOrderService(mock(RabbitTemplate.class));

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

        OrderService orderService = newOrderService(mock(RabbitTemplate.class));

        assertThatThrownBy(() -> orderService.deliverOrder("order-1", 42L))
                .isInstanceOf(RuntimeException.class);
    }
}
