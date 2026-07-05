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
                logger.info("Inicializando configuração RabbitMQ...");

                // Cria exchange
                Exchange exchange = ExchangeBuilder.topicExchange("order.exchange")
                        .durable(true)
                        .build();
                amqpAdmin.declareExchange(exchange);
                logger.info("Exchange criado: order.exchange");

                // Cria fila de reserva
                Queue reserveQueue = QueueBuilder.durable("inventory.reserve.queue")
                        .build();
                amqpAdmin.declareQueue(reserveQueue);
                logger.info("Fila criada: inventory.reserve.queue");

                // Binding para reserva
                Binding reserveBinding = BindingBuilder.bind(reserveQueue)
                        .to(exchange)
                        .with("stock.reserve")
                        .noargs();
                amqpAdmin.declareBinding(reserveBinding);
                logger.info("Binding criado: order.exchange -> stock.reserve -> inventory.reserve.queue");

                // Cria fila de liberação
                Queue releaseQueue = QueueBuilder.durable("inventory.release.queue")
                        .build();
                amqpAdmin.declareQueue(releaseQueue);
                logger.info("Fila criada: inventory.release.queue");

                // Binding para liberação
                Binding releaseBinding = BindingBuilder.bind(releaseQueue)
                        .to(exchange)
                        .with("stock.release")
                        .noargs();
                amqpAdmin.declareBinding(releaseBinding);
                logger.info("Binding criado: order.exchange -> stock.release -> inventory.release.queue");

                logger.info("Configuração RabbitMQ concluída com sucesso!");

            } catch (Exception e) {
                logger.error("Erro ao configurar RabbitMQ: {}", e.getMessage(), e);
                throw e;
            }
        };
    }
}