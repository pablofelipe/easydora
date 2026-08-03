package com.easydora.orders.messaging;

import com.easydora.orders.dto.OrderItemRequest;
import com.easydora.orders.dto.OrderRequest;
import com.easydora.orders.entity.Buyer;
import com.easydora.orders.entity.Order;
import com.easydora.orders.entity.OutboxEvent;
import com.easydora.orders.event.RefundPaymentCommand;
import com.easydora.orders.event.ReserveStockCommand;
import com.easydora.orders.repository.BuyerRepository;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.repository.OutboxEventRepository;
import com.easydora.orders.repository.ProductOwnershipRepository;
import com.easydora.orders.service.OrderService;
import com.easydora.orders.service.OrderStateMachineService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Behavior contract for orders-service's outbox-backed publishes (ADR-0007,
 * extended to all four routing keys by ADR-0034's Update/ADR-0037): every
 * one of order.created, stock.reserve, order.status-changed and
 * payment.refund.requested is written as an OutboxEvent row in the same
 * transaction as the domain change that produced it, never sent directly to
 * RabbitMQ. No assertion here inspects a RabbitMQ-specific wire detail --
 * only the business-level routing key and the decoded event body.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventsPublishBehaviorTest {

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
    void creatingAnOrderRecordsAnOrderCreatedEventInTheOutbox() {
        Buyer buyer = new Buyer();
        buyer.setUserId(42L);
        buyer.setActive(true);
        when(buyerRepository.findById(42L)).thenReturn(Optional.of(buyer));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent(anyString(), any(OrderEvent.class))).thenReturn(true);
        when(stateMachineService.getCurrentState(anyString())).thenReturn(OrderState.PROCESSING);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        OrderItemRequest item = new OrderItemRequest();
        item.setProductId("prod-1");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("10.00"));

        OrderRequest request = new OrderRequest();
        request.setItems(List.of(item));

        orderService.createOrder(request, 42L);

        assertThat(savedEvents)
                .withFailMessage("creating an order should record an order-created event in the outbox")
                .extracting(OutboxEvent::getRoutingKey)
                .contains("order.created");
    }

    @Test
    void creatingAnOrderRecordsAReserveStockCommandInTheOutbox() {
        Buyer buyer = new Buyer();
        buyer.setUserId(42L);
        buyer.setActive(true);
        when(buyerRepository.findById(42L)).thenReturn(Optional.of(buyer));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent(anyString(), any(OrderEvent.class))).thenReturn(true);
        when(stateMachineService.getCurrentState(anyString())).thenReturn(OrderState.PROCESSING);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        OrderItemRequest item = new OrderItemRequest();
        item.setProductId("prod-1");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("10.00"));

        OrderRequest request = new OrderRequest();
        request.setItems(List.of(item));

        orderService.createOrder(request, 42L);

        List<OutboxEvent> reserveStockEvents = savedEvents.stream()
                .filter(event -> event.getRoutingKey().equals("stock.reserve"))
                .toList();
        assertThat(reserveStockEvents)
                .withFailMessage("a processing order should record a ReserveStockCommand in the outbox")
                .hasSize(1);

        ReserveStockCommand command = OutboxEventCaptureSupport.bodyAs(reserveStockEvents.get(0), ReserveStockCommand.class);
        assertThat(command.getItems()).hasSize(1);
        assertThat(command.getItems().get(0).getProductId()).isEqualTo("prod-1");
    }

    @Test
    void inventoryReservationOutcomeRecordsAnOrderStatusChangedEventInTheOutbox() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.PROCESSING);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent("order-1", OrderEvent.INVENTORY_RESERVED)).thenReturn(true);
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.INVENTORY_RESERVED);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        orderService.handleInventoryReserved("order-1");

        assertThat(savedEvents)
                .withFailMessage("a stock-reservation outcome should record an order-status-changed event in the outbox")
                .extracting(OutboxEvent::getRoutingKey)
                .contains("order.status-changed");
    }

    @Test
    void strayPaymentApprovalCompensationRecordsARefundPaymentCommandInTheOutbox() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.CANCELLED);
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

        List<OutboxEvent> refundEvents = savedEvents.stream()
                .filter(event -> event.getRoutingKey().equals("payment.refund.requested"))
                .toList();
        assertThat(refundEvents)
                .withFailMessage("compensation must record a RefundPaymentCommand in the outbox")
                .hasSize(1);

        RefundPaymentCommand command = OutboxEventCaptureSupport.bodyAs(refundEvents.get(0), RefundPaymentCommand.class);
        assertThat(command.getOrderId()).isEqualTo("order-1");
    }
}
