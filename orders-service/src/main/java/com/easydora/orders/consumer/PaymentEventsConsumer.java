package com.easydora.orders.consumer;

import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationConstants;
import com.easydora.correlation.CorrelationContext;
import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.event.PaymentEvent;
import com.easydora.orders.service.OrderService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
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
    public void onPaymentApproved(
            PaymentEvent event,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
            BusinessEventLog.info(logger, "payment.approved.received", event.getOrderId(), "PaymentEvent (approved) received");
            orderService.handlePaymentReceived(event.getOrderId());
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_FAILED_QUEUE)
    public void onPaymentFailed(
            PaymentEvent event,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
            BusinessEventLog.info(logger, "payment.failed.received", event.getOrderId(),
                    "PaymentEvent (failed) received: reason=" + event.getFailureReason());
            orderService.handlePaymentFailed(event.getOrderId());
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }

    // ADR-0034: closes the compensation round-trip this service's own
    // publishRefundPaymentCommand (OrderService.initiateRefundCompensation)
    // opened. Reuses PaymentEvent (orderId/transactionId/failureReason) --
    // no new payload shape needed, the same fields already carry everything
    // both outcomes need.
    @RabbitListener(queues = RabbitMQConfig.PAYMENT_REFUNDED_QUEUE)
    public void onPaymentRefunded(
            PaymentEvent event,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
            BusinessEventLog.info(logger, "payment.refunded.received", event.getOrderId(), "PaymentEvent (refunded) received");
            orderService.handleRefundCompleted(event.getOrderId());
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_REFUND_FAILED_QUEUE)
    public void onPaymentRefundFailed(
            PaymentEvent event,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
            BusinessEventLog.info(logger, "payment.refund.failed.received", event.getOrderId(),
                    "PaymentEvent (refund failed) received: reason=" + event.getFailureReason());
            orderService.handleRefundFailed(event.getOrderId(), event.getFailureReason());
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }
}
