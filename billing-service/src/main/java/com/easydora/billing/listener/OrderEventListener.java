package com.easydora.billing.listener;

import com.easydora.billing.service.PaymentService;
import com.easydora.billing.messaging.events.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderEventListener.class);
    
    private final PaymentService paymentService;
    
    public OrderEventListener(PaymentService paymentService) {
        this.paymentService = paymentService;
    }
    
    @RabbitListener(queues = "${rabbitmq.queue.order-created}")
    public void handleOrderCreated(OrderCreatedEvent event) {
        logger.info("[RabbitMQ] Received OrderCreatedEvent - Order: {}", event.getOrderId());

        try {
            // Check whether a payment already exists
            boolean paymentExists = paymentService.checkIfPaymentExists(event.getOrderId().toString());

            if (!paymentExists) {
                // Create the pending payment
                paymentService.createPendingPayment(event);
                logger.info("[RabbitMQ] Pending payment created for order: {}", event.getOrderId());
            }
        } catch (Exception e) {
            logger.error("[RabbitMQ] Error processing OrderCreatedEvent: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process OrderCreatedEvent for order " + event.getOrderId(), e);
        }
    }
}