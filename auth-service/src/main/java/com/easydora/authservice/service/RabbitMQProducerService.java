package com.easydora.authservice.service;

import com.easydora.authservice.config.RabbitMQConfig;
import com.easydora.correlation.CorrelationMessaging;
import com.easydora.authservice.dto.JwtCreatedEvent;
import com.easydora.authservice.event.UserRegisteredEvent;

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

    public void sendUserRegisteredEvent(Long userId, String email, String firstName, String lastName, String role, String verificationToken) {
        UserRegisteredEvent event = new UserRegisteredEvent(userId, email, firstName, lastName, role, verificationToken);

        rabbitTemplate.convertAndSend(
            exchange.getName(),
            RabbitMQConfig.USER_REGISTERED_KEY,
            event,
            CorrelationMessaging.withCorrelation()
        );
    }
}