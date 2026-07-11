package com.easydora.orders.service;

import com.easydora.orders.entity.Buyer;
import com.easydora.orders.entity.Order;
import com.easydora.orders.event.OrderStatusChangedEvent;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * orderRepository.findById(orderId), called both here and again inside
 * OrderStateMachineService.sendEvent, resolves to the same Hibernate-
 * managed entity within one transaction -- so sendEvent's internal save
 * mutates the exact Order instance this class already holds a reference
 * to. Reading order.getState() for "previousState" *after* calling
 * sendEvent therefore always returns the already-mutated new state, not
 * the real previous one. Each test's mocked sendEvent mutates the shared
 * Order instance the same way the real one does, to reproduce that
 * without needing a live database.
 */
@ExtendWith(MockitoExtension.class)
class OrderServicePreviousStateTest {

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

        OrderStatusChangedEvent lastStatusChangedEvent() {
            return payloads.stream()
                    .filter(p -> p instanceof OrderStatusChangedEvent)
                    .map(p -> (OrderStatusChangedEvent) p)
                    .reduce((first, second) -> second)
                    .orElseThrow();
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
    void cancelOrder_publishesTheRealPreviousState() {
        Buyer buyer = new Buyer();
        buyer.setUserId(1L);
        buyer.setActive(true);
        when(buyerRepository.findById(1L)).thenReturn(Optional.of(buyer));

        Order order = new Order();
        order.setId("order-1");
        order.setUserId(1L);
        order.setState(OrderState.PENDING);
        when(orderRepository.findByIdAndUserId("order-1", 1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.isTransitionAllowed(OrderState.PENDING, OrderEvent.CANCEL_ORDER)).thenReturn(true);
        // Mirrors what the real OrderStateMachineService.sendEvent does: it
        // mutates the SAME managed Order instance in place before this
        // method ever reads order.getState() again.
        when(stateMachineService.sendEvent("order-1", OrderEvent.CANCEL_ORDER)).thenAnswer(invocation -> {
            order.setState(OrderState.CANCELLED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.CANCELLED);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.cancelOrder("order-1", 1L);

        OrderStatusChangedEvent published = rabbitTemplate.lastStatusChangedEvent();
        assertThat(published.getPreviousState()).isEqualTo(OrderState.PENDING);
        assertThat(published.getNewState()).isEqualTo(OrderState.CANCELLED);
    }

    @Test
    void handlePaymentReceived_publishesTheRealPreviousState() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.INVENTORY_RESERVED);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent("order-1", OrderEvent.PAYMENT_RECEIVED)).thenAnswer(invocation -> {
            order.setState(OrderState.PAYMENT_APPROVED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.PAYMENT_APPROVED);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.handlePaymentReceived("order-1");

        OrderStatusChangedEvent published = rabbitTemplate.lastStatusChangedEvent();
        assertThat(published.getPreviousState()).isEqualTo(OrderState.INVENTORY_RESERVED);
        assertThat(published.getNewState()).isEqualTo(OrderState.PAYMENT_APPROVED);
    }

    @Test
    void handlePaymentFailed_publishesTheRealPreviousState() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.INVENTORY_RESERVED);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent("order-1", OrderEvent.PAYMENT_FAILED)).thenAnswer(invocation -> {
            order.setState(OrderState.PAYMENT_FAILED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.PAYMENT_FAILED);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.handlePaymentFailed("order-1");

        OrderStatusChangedEvent published = rabbitTemplate.lastStatusChangedEvent();
        assertThat(published.getPreviousState()).isEqualTo(OrderState.INVENTORY_RESERVED);
        assertThat(published.getNewState()).isEqualTo(OrderState.PAYMENT_FAILED);
    }

    @Test
    void handleInventoryReserved_publishesTheRealPreviousState() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.PROCESSING);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent("order-1", OrderEvent.INVENTORY_RESERVED)).thenAnswer(invocation -> {
            order.setState(OrderState.INVENTORY_RESERVED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.INVENTORY_RESERVED);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.handleInventoryReserved("order-1");

        OrderStatusChangedEvent published = rabbitTemplate.lastStatusChangedEvent();
        assertThat(published.getPreviousState()).isEqualTo(OrderState.PROCESSING);
        assertThat(published.getNewState()).isEqualTo(OrderState.INVENTORY_RESERVED);
    }

    @Test
    void handleInventoryFailed_publishesTheRealPreviousState() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.PROCESSING);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent("order-1", OrderEvent.INVENTORY_FAILED)).thenAnswer(invocation -> {
            order.setState(OrderState.INVENTORY_FAILED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.INVENTORY_FAILED);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.handleInventoryFailed("order-1");

        OrderStatusChangedEvent published = rabbitTemplate.lastStatusChangedEvent();
        assertThat(published.getPreviousState()).isEqualTo(OrderState.PROCESSING);
        assertThat(published.getNewState()).isEqualTo(OrderState.INVENTORY_FAILED);
    }

    @Test
    void shipOrder_publishesTheRealPreviousState() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.PAYMENT_APPROVED);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.isTransitionAllowed(OrderState.PAYMENT_APPROVED, OrderEvent.SHIP_ORDER))
                .thenReturn(true);
        when(stateMachineService.sendEvent("order-1", OrderEvent.SHIP_ORDER)).thenAnswer(invocation -> {
            order.setState(OrderState.SHIPPED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.SHIPPED);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.shipOrder("order-1");

        OrderStatusChangedEvent published = rabbitTemplate.lastStatusChangedEvent();
        assertThat(published.getPreviousState()).isEqualTo(OrderState.PAYMENT_APPROVED);
        assertThat(published.getNewState()).isEqualTo(OrderState.SHIPPED);
    }

    @Test
    void deliverOrder_publishesTheRealPreviousState() {
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
        when(stateMachineService.sendEvent("order-1", OrderEvent.DELIVER_ORDER)).thenAnswer(invocation -> {
            order.setState(OrderState.DELIVERED);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.DELIVERED);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.deliverOrder("order-1", 42L);

        OrderStatusChangedEvent published = rabbitTemplate.lastStatusChangedEvent();
        assertThat(published.getPreviousState()).isEqualTo(OrderState.SHIPPED);
        assertThat(published.getNewState()).isEqualTo(OrderState.DELIVERED);
    }
}
