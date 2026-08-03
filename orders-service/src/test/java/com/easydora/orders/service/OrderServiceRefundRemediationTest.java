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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ADR-0034's own documented residual gap: "No remediation tooling exists
 * for the manual review a REFUND_FAILED order needs -- a genuine dead
 * end today." getRefundFailedQueue/retryRefund close it, mirroring the
 * existing getFulfillmentQueue/shipOrder pattern for platform-operations
 * actions.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceRefundRemediationTest {

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
    void getRefundFailedQueueReturnsOrdersInRefundFailedState() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.REFUND_FAILED);
        order.setRefundFailureReason("Payment not found for order order-1");
        when(orderRepository.findByState(OrderState.REFUND_FAILED)).thenReturn(List.of(order));

        List<com.easydora.orders.dto.OrderResponse> result = newOrderService().getRefundFailedQueue();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("order-1");
        assertThat(result.get(0).getRefundFailureReason()).isEqualTo("Payment not found for order order-1");
    }

    @Test
    void retryRefundSendsTheOrderBackThroughRefundingAndRepublishesTheCommand() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.REFUND_FAILED);
        order.setRefundFailureReason("Payment not found for order order-1");
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent("order-1", OrderEvent.RETRY_REFUND)).thenAnswer(invocation -> {
            order.setState(OrderState.REFUNDING);
            return true;
        });
        when(stateMachineService.getCurrentState("order-1")).thenReturn(OrderState.REFUNDING);

        List<OutboxEvent> savedEvents = OutboxEventCaptureSupport.capture(outboxEventRepository);
        OrderService orderService = newOrderService();

        var response = orderService.retryRefund("order-1");

        assertThat(order.getState()).isEqualTo(OrderState.REFUNDING);
        assertThat(order.getRefundFailureReason())
                .withFailMessage("the stale failure reason should be cleared once compensation is retried")
                .isNull();
        assertThat(response.getState()).isEqualTo(OrderState.REFUNDING);

        List<OrderStatusChangedEvent> statusEvents = savedEvents.stream()
                .filter(event -> event.getRoutingKey().equals("order.status-changed"))
                .map(event -> OutboxEventCaptureSupport.bodyAs(event, OrderStatusChangedEvent.class))
                .toList();
        assertThat(statusEvents).hasSize(1);
        assertThat(statusEvents.get(0).getPreviousState()).isEqualTo(OrderState.REFUND_FAILED);
        assertThat(statusEvents.get(0).getNewState()).isEqualTo(OrderState.REFUNDING);

        List<RefundPaymentCommand> refundCommands = savedEvents.stream()
                .filter(event -> event.getRoutingKey().equals("payment.refund.requested"))
                .map(event -> OutboxEventCaptureSupport.bodyAs(event, RefundPaymentCommand.class))
                .toList();
        assertThat(refundCommands).hasSize(1);
        assertThat(refundCommands.get(0).getOrderId()).isEqualTo("order-1");
    }

    @Test
    void retryRefundThrowsWhenTheOrderIsNotInRefundFailedState() {
        Order order = new Order();
        order.setId("order-1");
        order.setState(OrderState.REFUNDING);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(stateMachineService.sendEvent("order-1", OrderEvent.RETRY_REFUND)).thenReturn(false);

        OrderService orderService = newOrderService();

        assertThatThrownBy(() -> orderService.retryRefund("order-1"))
                .isInstanceOf(RuntimeException.class);
    }
}
