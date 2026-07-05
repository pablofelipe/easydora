package com.easydora.billing.messaging.consumer;

import com.easydora.billing.service.PaymentService;
import com.easydora.billing.messaging.events.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedConsumer {

    private static final Logger logger = LoggerFactory.getLogger(OrderCreatedConsumer.class);
    
    private final PaymentService paymentService;
    
    public OrderCreatedConsumer(PaymentService paymentService) {
        this.paymentService = paymentService;
    }
    
    @KafkaListener(
        topics = "order.created.topic",
        groupId = "billing-service-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleOrderCreated(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {
        
        try {
            logger.info("[BILLING] Received OrderCreatedEvent - Order: {}, User: {}, Total: {}",
                event.getOrderId(), event.getUserId(), event.getTotalAmount());
            logger.info("   Partition: {}, Offset: {}, Key: {}", partition, offset, key);

            // Check whether a payment already exists for this order
            boolean paymentExists = paymentService.checkIfPaymentExists(event.getOrderId().toString());

            if (paymentExists) {
                logger.warn("[BILLING] Payment already exists for order: {}", event.getOrderId());
            } else {
                // Create the pending payment record
                paymentService.createPendingPayment(event);
                logger.info("[BILLING] Pending payment created for order: {}", event.getOrderId());
            }

            // Confirm processing
            ack.acknowledge();
            logger.info("[BILLING] Offset committed for order: {}", event.getOrderId());

        } catch (Exception e) {
            logger.error("[BILLING] Error processing OrderCreatedEvent for order {}: {}",
                event.getOrderId(), e.getMessage(), e);
            // We don't ack, so it gets retried
        }
    }
}