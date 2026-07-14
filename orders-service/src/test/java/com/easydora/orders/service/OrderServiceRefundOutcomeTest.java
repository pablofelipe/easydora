package com.easydora.orders.service;

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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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

        List<OrderStatusChangedEvent> statusChangedEvents() {
            return payloads.stream()
                    .filter(OrderStatusChangedEvent.class::isInstance)
                    .map(OrderStatusChangedEvent.class::cast)
                    .toList();
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
                productOwnershipRepository, new SimpleMeterRegistry());
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

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.handleRefundCompleted("order-1");

        assertThat(order.getState()).isEqualTo(OrderState.REFUNDED);
        List<OrderStatusChangedEvent> events = rabbitTemplate.statusChangedEvents();
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

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.handleRefundFailed("order-1", "Payment not found for order order-1");

        assertThat(order.getState()).isEqualTo(OrderState.REFUND_FAILED);
        List<OrderStatusChangedEvent> events = rabbitTemplate.statusChangedEvents();
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

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = newOrderService(rabbitTemplate);

        orderService.handleRefundCompleted("order-1");

        verify(orderRepository, never()).saveAndFlush(any(Order.class));
        assertThat(rabbitTemplate.statusChangedEvents()).isEmpty();
    }
}
