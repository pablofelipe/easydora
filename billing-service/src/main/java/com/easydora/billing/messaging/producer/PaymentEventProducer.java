package com.easydora.billing.messaging.producer;

import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.billing.messaging.events.PaymentProcessedEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void sendPaymentProcessedEvent(PaymentProcessedEvent event) {
        String routingKey = event.getStatus().equals("APPROVED") 
            ? RabbitMQConfig.PAYMENT_APPROVED_KEY 
            : RabbitMQConfig.PAYMENT_FAILED_KEY;
            
        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, routingKey, event);
        System.out.println("📤 Evento de pagamento enviado: " + routingKey + " - Order: " + event.getOrderId());
    }
}