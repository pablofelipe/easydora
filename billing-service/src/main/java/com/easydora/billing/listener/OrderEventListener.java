package com.easydora.billing.listener;

import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.billing.service.PaymentService;
import com.easydora.billing.messaging.events.OrderCreatedEvent;
import com.easydora.billing.messaging.events.RefundPaymentCommand;
import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationConstants;
import com.easydora.correlation.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {

    private static final Logger logger = LoggerFactory.getLogger(OrderEventListener.class);

    private final PaymentService paymentService;

    public OrderEventListener(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @RabbitListener(queues = "${rabbitmq.queue.order-created}")
    public void handleOrderCreated(
            OrderCreatedEvent event,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
            BusinessEventLog.info(logger, "order.created.received", event.getOrderId(), "Received OrderCreatedEvent");

            try {
                // Check whether a payment already exists
                boolean paymentExists = paymentService.checkIfPaymentExists(event.getOrderId().toString());

                if (!paymentExists) {
                    // Create the pending payment
                    paymentService.createPendingPayment(event);
                    BusinessEventLog.info(logger, "payment.pending.created", event.getOrderId(), "Pending payment created");
                }
            } catch (Exception e) {
                logger.error("[RabbitMQ] Error processing OrderCreatedEvent: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to process OrderCreatedEvent for order " + event.getOrderId(), e);
            }
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }

    // ADR-0034: orders-service instructing this service to refund a
    // payment it already approved for an order that can no longer be
    // honored. See PaymentService.refundPayment for the actual decision.
    @RabbitListener(queues = RabbitMQConfig.REFUND_PAYMENT_REQUESTED_QUEUE)
    public void handleRefundPaymentRequested(
            RefundPaymentCommand command,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
            BusinessEventLog.info(logger, "payment.refund.requested.received", command.getOrderId(),
                    "RefundPaymentCommand received");
            paymentService.refundPayment(command.getOrderId());
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }
}