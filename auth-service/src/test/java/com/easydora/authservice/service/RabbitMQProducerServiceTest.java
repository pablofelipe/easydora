package com.easydora.authservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Both direct publishes must go through the correlation-aware
 * MessagePostProcessor overload of convertAndSend, not the bare 3-arg one
 * -- otherwise correlationId/messageId never make it onto the wire.
 */
class RabbitMQProducerServiceTest {

    @Test
    void sendJwtCreatedEventPublishesWithAMessagePostProcessor() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        TopicExchange exchange = mock(TopicExchange.class);
        when(exchange.getName()).thenReturn("auth.exchange");

        RabbitMQProducerService service = new RabbitMQProducerService(rabbitTemplate, exchange);
        service.sendJwtCreatedEvent("token", 1L, "e@x.com", "First", "Last", "BUYER", 3600L);

        verify(rabbitTemplate).convertAndSend(eq("auth.exchange"), anyString(), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void sendUserRegisteredEventPublishesWithAMessagePostProcessor() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        TopicExchange exchange = mock(TopicExchange.class);
        when(exchange.getName()).thenReturn("auth.exchange");

        RabbitMQProducerService service = new RabbitMQProducerService(rabbitTemplate, exchange);
        service.sendUserRegisteredEvent(1L, "e@x.com", "First", "Last", "BUYER", "token");

        verify(rabbitTemplate).convertAndSend(eq("auth.exchange"), anyString(), any(Object.class), any(MessagePostProcessor.class));
    }
}
