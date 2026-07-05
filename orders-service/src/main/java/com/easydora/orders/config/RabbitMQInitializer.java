package com.easydora.orders.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQInitializer {
    
    private static final Logger logger = LoggerFactory.getLogger(RabbitMQInitializer.class);
    
    @Bean
    public ApplicationRunner initializeRabbitMQ(AmqpAdmin amqpAdmin) {
        return args -> {
            try {
                logger.info("Initializing RabbitMQ configuration...");

                // Create exchange
                Exchange exchange = ExchangeBuilder.topicExchange("order.exchange")
                        .durable(true)
                        .build();
                amqpAdmin.declareExchange(exchange);
                logger.info("Exchange created: order.exchange");

                // Create reserve queue
                Queue reserveQueue = QueueBuilder.durable("inventory.reserve.queue")
                        .build();
                amqpAdmin.declareQueue(reserveQueue);
                logger.info("Queue created: inventory.reserve.queue");

                // Binding for reserve
                Binding reserveBinding = BindingBuilder.bind(reserveQueue)
                        .to(exchange)
                        .with("stock.reserve")
                        .noargs();
                amqpAdmin.declareBinding(reserveBinding);
                logger.info("Binding created: order.exchange -> stock.reserve -> inventory.reserve.queue");

                // Create release queue
                Queue releaseQueue = QueueBuilder.durable("inventory.release.queue")
                        .build();
                amqpAdmin.declareQueue(releaseQueue);
                logger.info("Queue created: inventory.release.queue");

                // Binding for release
                Binding releaseBinding = BindingBuilder.bind(releaseQueue)
                        .to(exchange)
                        .with("stock.release")
                        .noargs();
                amqpAdmin.declareBinding(releaseBinding);
                logger.info("Binding created: order.exchange -> stock.release -> inventory.release.queue");

                logger.info("RabbitMQ configuration completed successfully!");

            } catch (Exception e) {
                logger.error("Error configuring RabbitMQ: {}", e.getMessage(), e);
                throw e;
            }
        };
    }
}