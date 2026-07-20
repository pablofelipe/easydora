package com.easydora.products.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class RabbitMQConfig {

    // auth-service exchange
    public static final String AUTH_EXCHANGE = "auth.exchange";
    
    // auth-service routing keys
    public static final String JWT_ROUTING_KEY = "jwt.created";
    public static final String USER_REGISTERED_KEY = "user.registered";
    public static final String USER_VERIFIED_KEY = "user.verified";
    
    // products-service-specific queues
    public static final String USER_VERIFIED_QUEUE = "products.user.verified.queue";
    public static final String USER_REGISTERED_QUEUE = "products.user.registered.queue";
    public static final String JWT_CREATED_QUEUE = "products.jwt.created.queue";

    // product.* domain events (ADR-0007) - consumed by inventory-service
    public static final String PRODUCT_EXCHANGE = "product.exchange";
    public static final String PRODUCT_CREATED_KEY = "product.created";
    public static final String PRODUCT_UPDATED_KEY = "product.updated";
    public static final String PRODUCT_DELETED_KEY = "product.deleted";

    // Dead letter routing - every listener queue in this service
    // shares one DLX/DLQ pair; RepublishMessageRecoverer republishes using
    // the original received routing key, so the DLQ binds on "#" to catch
    // whichever queue's message was rejected after exhausting retries.
    public static final String DLX_EXCHANGE = "products.dlx";
    public static final String DLQ = "products.dlq";

    @Bean
    public TopicExchange authExchange() {
        return new TopicExchange(AUTH_EXCHANGE);
    }

    @Bean
    public TopicExchange productExchange() {
        return new TopicExchange(PRODUCT_EXCHANGE);
    }

    @Bean
    public Queue userVerifiedQueue() {
        return new Queue(USER_VERIFIED_QUEUE, true);
    }

    @Bean
    public Queue userRegisteredQueue() {
        return new Queue(USER_REGISTERED_QUEUE, true);
    }

    @Bean
    public Queue jwtCreatedQueue() {
        return new Queue(JWT_CREATED_QUEUE, true);
    }

    @Bean
    public Binding userVerifiedBinding(Queue userVerifiedQueue, TopicExchange authExchange) {
        return BindingBuilder.bind(userVerifiedQueue)
                .to(authExchange)
                .with(USER_VERIFIED_KEY);
    }

    @Bean
    public Binding userRegisteredBinding(Queue userRegisteredQueue, TopicExchange authExchange) {
        return BindingBuilder.bind(userRegisteredQueue)
                .to(authExchange)
                .with(USER_REGISTERED_KEY);
    }

    @Bean
    public Binding jwtCreatedBinding(Queue jwtCreatedQueue, TopicExchange authExchange) {
        return BindingBuilder.bind(jwtCreatedQueue)
                .to(authExchange)
                .with(JWT_ROUTING_KEY);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue deadLetterQueue() {
        return new Queue(DLQ, true);
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with("#");
    }

    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, DLX_EXCHANGE);
    }

    @Bean
    public MessageConverter messageConverter() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(DefaultJackson2JavaTypeMapper.TypePrecedence.INFERRED);
        converter.setJavaTypeMapper(typeMapper);

        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        return rabbitTemplate;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        // Applies spring.rabbitmq.listener.simple.retry.* (limited attempts,
        // exponential backoff) and wires the messageRecoverer bean above as
        // the recoverer used once retries are exhausted - no custom retry
        // code, just Spring Boot's native listener container configurer.
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter());
        // Publishes ListenerContainerIdleEvent every 30s even with no
        // messages flowing -- the signal RabbitMqProgressEventListener/
        // ProgressWatchdog use to prove the container's own loop is still
        // alive (docs/adr/0038's Update), independent of whether RabbitMQ
        // itself is reachable.
        factory.setIdleEventInterval(30_000L);
        return factory;
    }

    @Bean
    public java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }

    // Reconnection observability (docs/adr/0038's Update): registers
    // RabbitMqReconnectionMetrics as a ConnectionListener on the
    // autoconfigured ConnectionFactory -- observes Automatic Connection
    // Recovery, does not reimplement it.
    @Bean
    public RabbitMqReconnectionMetrics rabbitMqReconnectionMetrics(
            ConnectionFactory connectionFactory, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        RabbitMqReconnectionMetrics listener = new RabbitMqReconnectionMetrics(meterRegistryProvider);
        connectionFactory.addConnectionListener(listener);
        return listener;
    }
}