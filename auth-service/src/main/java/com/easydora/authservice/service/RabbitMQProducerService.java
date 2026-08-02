package com.easydora.authservice.service;

import com.easydora.authservice.config.RabbitMQConfig;
import com.easydora.correlation.CorrelationMessaging;
import com.easydora.authservice.dto.JwtCreatedEvent;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
@Service
public class RabbitMQProducerService {

    private final RabbitTemplate rabbitTemplate;
    private final TopicExchange exchange;

    public RabbitMQProducerService(RabbitTemplate rabbitTemplate, TopicExchange exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    public void sendJwtCreatedEvent(String token, Long userId, String email, String firstName, String lastName, String role, Long expiresIn) {
        JwtCreatedEvent event = new JwtCreatedEvent(token, userId, email, firstName, lastName, role, LocalDateTime.now(), expiresIn);

        rabbitTemplate.convertAndSend(
            exchange.getName(),
            RabbitMQConfig.JWT_ROUTING_KEY,
            event,
            CorrelationMessaging.withCorrelation()
        );
    }
}