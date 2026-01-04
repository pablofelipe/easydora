package com.easydora.billing.listener;

import com.easydora.billing.messaging.events.OrderCreatedEvent;
import com.easydora.billing.messaging.events.PaymentProcessedEvent;
import com.easydora.billing.service.PaymentService;
import com.easydora.billing.service.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class OrderEventListener {
    
    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);
    
    private final PaymentService paymentService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    public OrderEventListener(PaymentService paymentService, KafkaTemplate<String, Object> kafkaTemplate) {
        this.paymentService = paymentService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "order.created", groupId = "billing-service-group")
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("📥 Received OrderCreatedEvent for order: {}", event.getOrderId());
        
        try {
            // Processar pagamento
            PaymentResult result = paymentService.processPayment(
                event.getOrderId().toString(), 
                event.getUserId().toString(), 
                event.getTotalAmount()
            );
            
            // Publicar evento de pagamento processado
            PaymentProcessedEvent paymentEvent = new PaymentProcessedEvent();
            paymentEvent.setPaymentId(UUID.randomUUID().toString());
            paymentEvent.setOrderId(event.getOrderId());
            paymentEvent.setAmount(event.getTotalAmount());
            paymentEvent.setStatus(result.isSuccess() ? 
                PaymentProcessedEvent.PaymentStatus.APPROVED : 
                PaymentProcessedEvent.PaymentStatus.FAILED);
            paymentEvent.setTransactionId(result.getTransactionId());
            paymentEvent.setFailureReason(result.getErrorMessage());
            paymentEvent.setProcessedAt(LocalDateTime.now());
            
            kafkaTemplate.send("payment.processed", paymentEvent);
            
            log.info("✅ Payment processed for order {}: {}", 
                event.getOrderId(), result.isSuccess() ? "APPROVED" : "FAILED");
            
        } catch (Exception e) {
            log.error("❌ Error processing payment for order {}: {}", 
                event.getOrderId(), e.getMessage(), e);
            
            // Publicar evento de falha
            PaymentProcessedEvent failureEvent = new PaymentProcessedEvent();
            failureEvent.setPaymentId(UUID.randomUUID().toString());
            failureEvent.setOrderId(event.getOrderId());
            failureEvent.setAmount(event.getTotalAmount());
            failureEvent.setStatus(PaymentProcessedEvent.PaymentStatus.FAILED);
            failureEvent.setFailureReason("Internal server error: " + e.getMessage());
            failureEvent.setProcessedAt(LocalDateTime.now());
            
            kafkaTemplate.send("payment.processed", failureEvent);
        }
    }
}