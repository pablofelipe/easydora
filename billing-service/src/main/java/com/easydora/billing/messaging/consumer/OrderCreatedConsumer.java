package com.easydora.billing.messaging.consumer;

import com.easydora.billing.messaging.events.OrderCreatedEvent;
import com.easydora.billing.service.PaymentService;
// import com.easydora.billing.messaging.config.RabbitMQConfig;
import com.easydora.billing.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedConsumer {

    @Autowired
    private PaymentService paymentService;

    @RabbitListener(queues = RabbitMQConfig.ORDER_CREATED_QUEUE)
    public void handleOrderCreated(OrderCreatedEvent event) {
        System.out.println("📦 Recebido OrderCreatedEvent para orderId: " + event.getOrderId());
        
        try {
            // Processar pagamento
            paymentService.processPayment(event.getOrderId(), event.getTotalAmount());
            System.out.println("✅ Pagamento processado para orderId: " + event.getOrderId());
        } catch (Exception e) {
            System.err.println("❌ Erro ao processar pagamento para orderId: " + event.getOrderId() + " - " + e.getMessage());
        }
    }
}