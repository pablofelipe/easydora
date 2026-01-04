package com.easydora.billing.messaging.consumer;

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
public class OrderCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedConsumer.class);
    
    private final PaymentService paymentService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    // Construtor com injeção de dependências
    public OrderCreatedConsumer(PaymentService paymentService, 
                                KafkaTemplate<String, Object> kafkaTemplate) {
        this.paymentService = paymentService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
        topics = "order.created", 
        groupId = "billing-service-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("📥 Recebido OrderCreatedEvent - Pedido: {}, Usuário: {}, Valor: {}", 
            event.getOrderId(), event.getUserId(), event.getTotalAmount());
        
        try {
            // Processar pagamento
            PaymentResult result = paymentService.processPayment(
                event.getOrderId().toString(),
                event.getUserId().toString(),
                event.getTotalAmount()
            );
            
            // Criar evento de pagamento processado
            PaymentProcessedEvent paymentEvent = createPaymentProcessedEvent(event, result);
            
            // Publicar no Kafka
            kafkaTemplate.send("payment.processed", paymentEvent);
            
            if (result.isSuccess()) {
                log.info("✅ Pagamento APROVADO para pedido {} - Transação: {}", 
                    event.getOrderId(), result.getTransactionId());
            } else {
                log.warn("⚠️ Pagamento REPROVADO para pedido {} - Motivo: {}", 
                    event.getOrderId(), result.getErrorMessage());
            }
            
        } catch (Exception e) {
            log.error("❌ ERRO ao processar pagamento para pedido {}: {}", 
                event.getOrderId(), e.getMessage(), e);
            
            // Publicar evento de falha
            PaymentProcessedEvent failureEvent = createFailureEvent(event, e);
            kafkaTemplate.send("payment.processed", failureEvent);
        }
    }
    
    private PaymentProcessedEvent createPaymentProcessedEvent(
            OrderCreatedEvent orderEvent, PaymentResult result) {
        
        PaymentProcessedEvent event = new PaymentProcessedEvent();
        event.setPaymentId(UUID.randomUUID().toString());
        event.setOrderId(orderEvent.getOrderId());
        event.setAmount(orderEvent.getTotalAmount());
        event.setStatus(result.isSuccess() ? 
            PaymentProcessedEvent.PaymentStatus.APPROVED : 
            PaymentProcessedEvent.PaymentStatus.FAILED);
        event.setTransactionId(result.getTransactionId());
        event.setFailureReason(result.getErrorMessage());
        event.setProcessedAt(LocalDateTime.now());
        
        return event;
    }
    
    private PaymentProcessedEvent createFailureEvent(OrderCreatedEvent orderEvent, Exception e) {
        PaymentProcessedEvent event = new PaymentProcessedEvent();
        event.setPaymentId(UUID.randomUUID().toString());
        event.setOrderId(orderEvent.getOrderId());
        event.setAmount(orderEvent.getTotalAmount());
        event.setStatus(PaymentProcessedEvent.PaymentStatus.FAILED);
        event.setFailureReason("Erro interno: " + e.getMessage());
        event.setProcessedAt(LocalDateTime.now());
        
        return event;
    }
}