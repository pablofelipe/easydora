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
        logger.info("📥 [RabbitMQ] Recebido OrderCreatedEvent - Order: {}", event.getOrderId());
        
        try {
            // Verificar se já existe pagamento
            boolean paymentExists = paymentService.checkIfPaymentExists(event.getOrderId().toString());
            
            if (!paymentExists) {
                // Criar pagamento pendente
                paymentService.createPendingPayment(event);
                logger.info("✅ [RabbitMQ] Pagamento pendente criado para order: {}", event.getOrderId());
            }
        } catch (Exception e) {
            logger.error("❌ [RabbitMQ] Erro ao processar OrderCreatedEvent: {}", e.getMessage(), e);
        }
    }
}