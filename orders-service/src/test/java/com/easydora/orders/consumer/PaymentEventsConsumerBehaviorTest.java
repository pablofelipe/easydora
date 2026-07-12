package com.easydora.orders.consumer;

import com.easydora.orders.event.PaymentEvent;
import com.easydora.orders.service.OrderService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/**
 * Behavior contract for the billing -> orders payment outcome hop: each
 * queue must trigger the matching, already-existing OrderService method --
 * no new business logic here, just wiring a real consumer to methods that
 * previously had no caller (ADR-0001, finding 5).
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventsConsumerBehaviorTest {

    @Mock
    private OrderService orderService;

    @Test
    void paymentApprovedCallsHandlePaymentReceived() {
        PaymentEventsConsumer consumer = new PaymentEventsConsumer(orderService);

        PaymentEvent event = new PaymentEvent();
        event.setOrderId("order-123");
        event.setTransactionId("txn-1");

        consumer.onPaymentApproved(event, "corr-1", "msg-1");

        verify(orderService).handlePaymentReceived("order-123");
    }

    @Test
    void paymentFailedCallsHandlePaymentFailed() {
        PaymentEventsConsumer consumer = new PaymentEventsConsumer(orderService);

        PaymentEvent event = new PaymentEvent();
        event.setOrderId("order-456");
        event.setFailureReason("Payment declined by the processor");

        consumer.onPaymentFailed(event, "corr-2", "msg-2");

        verify(orderService).handlePaymentFailed("order-456");
    }

    @Test
    void paymentRefundedCallsHandleRefundCompleted() {
        PaymentEventsConsumer consumer = new PaymentEventsConsumer(orderService);

        PaymentEvent event = new PaymentEvent();
        event.setOrderId("order-789");
        event.setTransactionId("txn-9");

        consumer.onPaymentRefunded(event, "corr-3", "msg-3");

        verify(orderService).handleRefundCompleted("order-789");
    }

    @Test
    void paymentRefundFailedCallsHandleRefundFailed() {
        PaymentEventsConsumer consumer = new PaymentEventsConsumer(orderService);

        PaymentEvent event = new PaymentEvent();
        event.setOrderId("order-321");
        event.setFailureReason("Payment not found for order order-321");

        consumer.onPaymentRefundFailed(event, "corr-4", "msg-4");

        verify(orderService).handleRefundFailed("order-321", "Payment not found for order order-321");
    }
}
