package com.easydora.orders.consumer;

import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.service.OrderService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;


@Component
public class PaymentEventsConsumer {
    
    private final OrderService orderService;
    
    public PaymentEventsConsumer(OrderService orderService) {
        this.orderService = orderService;
    }
    
    @RabbitListener(queues = RabbitMQConfig.PAYMENT_APPROVED_QUEUE)
    public void handlePaymentApproved(String orderId) {
        orderService.handlePaymentReceived(orderId);
    }
    
    @RabbitListener(queues = RabbitMQConfig.PAYMENT_FAILED_QUEUE) 
    public void handlePaymentFailed(String orderId) {
        orderService.handlePaymentFailed(orderId);
    }
}