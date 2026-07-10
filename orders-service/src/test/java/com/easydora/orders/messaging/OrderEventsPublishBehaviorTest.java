package com.easydora.orders.messaging;

import com.easydora.orders.dto.OrderItemRequest;
import com.easydora.orders.dto.OrderRequest;
import com.easydora.orders.entity.Buyer;
import com.easydora.orders.entity.Order;
import com.easydora.orders.repository.BuyerRepository;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.repository.ProductOwnershipRepository;
import com.easydora.orders.service.OrderService;
import com.easydora.orders.service.OrderStateMachineService;
import com.easydora.orders.statemachine.OrderEvent;
import com.easydora.orders.statemachine.OrderState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behavior contract for orders-service's two outbound hops covered by
 * ADR-0007: order.created (consumed by billing-service) and
 * order.status-changed (currently consumer-less, ADR-0001). No assertion
 * here inspects a RabbitMQ-specific wire detail — only the business-level
 * routing key.
 *
 * RecordingRabbitTemplate is a test double for OrderService's real
 * RabbitTemplate dependency: it overrides convertAndSend() to capture the
 * call instead of touching a real broker, so this test exercises the real
 * createOrder/handleInventoryReserved methods against production code,
 * unchanged.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventsPublishBehaviorTest {

    private static class RecordingRabbitTemplate extends RabbitTemplate {
        final List<String> routingKeys = new ArrayList<>();
        final List<Object> payloads = new ArrayList<>();

        RecordingRabbitTemplate() {
            super(mock(ConnectionFactory.class));
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object) {
            routingKeys.add(routingKey);
            payloads.add(object);
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object, MessagePostProcessor messagePostProcessor) {
            routingKeys.add(routingKey);
            payloads.add(object);
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

    @Test
    void creatingAnOrderPublishesAnOrderCreatedEvent() {
        Buyer buyer = new Buyer();
        buyer.setUserId(42L);
        buyer.setActive(true);
        when(buyerRepository.findById(42L)).thenReturn(Optional.of(buyer));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent(anyString(), any(OrderEvent.class))).thenReturn(true);
        when(stateMachineService.getCurrentState(anyString())).thenReturn(OrderState.PROCESSING);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = new OrderService(
                buyerRepository, orderRepository, stateMachineService, rabbitTemplate,
                productOwnershipRepository);

        OrderItemRequest item = new OrderItemRequest();
        item.setProductId("prod-1");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("10.00"));

        OrderRequest request = new OrderRequest();
        request.setItems(List.of(item));

        orderService.createOrder(request, 42L);

        assertThat(rabbitTemplate.routingKeys)
                .withFailMessage("creating an order should publish an order-created event")
                .contains("order.created");
    }

    @Test
    void inventoryReservationOutcomePublishesAnOrderStatusChangedEvent() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.PROCESSING);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent("order-1", OrderEvent.INVENTORY_RESERVED)).thenReturn(true);
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.INVENTORY_RESERVED);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = new OrderService(
                buyerRepository, orderRepository, stateMachineService, rabbitTemplate,
                productOwnershipRepository);

        orderService.handleInventoryReserved("order-1");

        assertThat(rabbitTemplate.routingKeys)
                .withFailMessage("a stock-reservation outcome should publish an order-status-changed event")
                .contains("order.status-changed");
    }
}
