package com.easydora.orders.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Component
public class RabbitMQHealthIndicator implements HealthIndicator {
    
    private final RabbitTemplate rabbitTemplate;
    private boolean rabbitMQConfigured = false;
    
    public RabbitMQHealthIndicator(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }
    
    @Override
    public Health health() {
        // First, check whether RabbitMQ is reachable
        try {
            // Try a simple operation
            rabbitTemplate.execute(channel -> {
                // Check whether the exchange exists
                try {
                    channel.exchangeDeclarePassive("order.exchange");
                    rabbitMQConfigured = true;
                    return null;
                } catch (Exception e) {
                    // If it doesn't exist, try to create it
                    channel.exchangeDeclare("order.exchange", "direct", true);

                    // Create the queues
                    channel.queueDeclare("inventory.reserve.queue", true, false, false, null);
                    channel.queueDeclare("inventory.release.queue", true, false, false, null);

                    // Create the bindings
                    channel.queueBind("inventory.reserve.queue", "order.exchange", "stock.reserve");
                    channel.queueBind("inventory.release.queue", "order.exchange", "stock.release");
                    
                    rabbitMQConfigured = true;
                    return null;
                }
            });
            
            if (rabbitMQConfigured) {
                return Health.up()
                    .withDetail("rabbitmq", "connected")
                    .withDetail("exchange", "order.exchange")
                    .withDetail("queues", "inventory.reserve.queue, inventory.release.queue")
                    .build();
            } else {
                return Health.down()
                    .withDetail("rabbitmq", "connecting")
                    .build();
            }
            
        } catch (Exception e) {
            return Health.down()
                .withDetail("rabbitmq", "not_connected")
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}