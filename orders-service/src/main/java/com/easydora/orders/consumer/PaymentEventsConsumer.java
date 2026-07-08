package com.easydora.orders.consumer;

import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.event.PaymentEvent;
import com.easydora.orders.service.OrderService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes billing-service's payment outcome events and drives the same
 * state-machine transitions OrderService.handlePaymentReceived/
 * handlePaymentFailed already implement. Previously unreachable: ADR-0001
 * (finding 5) removed the prior PaymentEventProducer/PaymentEventsConsumer
 * pair as dead code with a type mismatch (Long vs String orderId) --
 * this is a fresh, correctly-typed implementation, not a resurrection of
 * that one.
 */
@Component
public class PaymentEventsConsumer {

    private static final Logger logger = LoggerFactory.getLogger(PaymentEventsConsumer.class);

    private final OrderService orderService;

    public PaymentEventsConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_APPROVED_QUEUE)
    public void onPaymentApproved(PaymentEvent event) {
        logger.info("[RABBITMQ] PaymentEvent (approved) received: orderId={}", event.getOrderId());
        orderService.handlePaymentReceived(event.getOrderId());
    }

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_FAILED_QUEUE)
    public void onPaymentFailed(PaymentEvent event) {
        logger.info("[RABBITMQ] PaymentEvent (failed) received: orderId={}, reason={}",
                event.getOrderId(), event.getFailureReason());
        orderService.handlePaymentFailed(event.getOrderId());
    }
}
