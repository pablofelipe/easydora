package com.easydora.orders.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;

@Configuration
public class RabbitMQConfig {

    // auth-service exchange
    public static final String AUTH_EXCHANGE = "auth.exchange";

    // auth-service routing keys
    public static final String JWT_ROUTING_KEY = "jwt.created";
    public static final String USER_REGISTERED_KEY = "user.registered";
    public static final String USER_VERIFIED_KEY = "user.verified";

    // orders-service-specific queues
    public static final String USER_VERIFIED_QUEUE = "orders.user.verified.queue";
    public static final String USER_REGISTERED_QUEUE = "orders.user.registered.queue";
    public static final String JWT_CREATED_QUEUE = "orders.jwt.created.queue";
    // Separate queue for UserEventsConsumer's profile-update handling of the
    // same jwt.created event. JwtConsumer (session/auth) and
    // UserEventsConsumer (profile update) used to share JWT_CREATED_QUEUE,
    // so RabbitMQ round-robinned each individual message to only one of the
    // two competing consumers, silently dropping ~50% of deliveries for
    // each. Both queues bind to the same routing key below, so the topic
    // exchange fans out a copy of every jwt.created message to each queue
    // independently — the publisher (auth-service) is unchanged.
    public static final String JWT_CREATED_PROFILE_QUEUE = "orders.jwt.created.profile.queue";

    // Exchange/Queues for orders-service commands
    public static final String ORDER_EXCHANGE = "order.exchange";
    public static final String INVENTORY_RESERVE_QUEUE = "inventory.reserve.queue";
    public static final String RESERVE_STOCK_ROUTING_KEY = "stock.reserve";

    // order.* domain events (ADR-0007) - published by OrderService
    public static final String ORDER_CREATED_KEY = "order.created";
    public static final String ORDER_STATUS_CHANGED_KEY = "order.status-changed";

    // stock.* outcome events (ADR-0007) - published by inventory-service's
    // Outbox, consumed here to drive the order state machine
    public static final String STOCK_RESERVED_QUEUE = "orders.stock.reserved.queue";
    public static final String STOCK_RESERVED_ROUTING_KEY = "stock.reserved";
    public static final String STOCK_INSUFFICIENT_QUEUE = "orders.stock.insufficient.queue";
    public static final String STOCK_INSUFFICIENT_ROUTING_KEY = "stock.insufficient";

    // products-service's product.exchange (ADR-0026's Etapa 27): only
    // product.created is consumed today -- ownership is set once, at
    // creation time, and never anticipated to move. See ProductCreatedEvent.
    public static final String PRODUCT_EXCHANGE = "product.exchange";
    public static final String PRODUCT_CREATED_QUEUE = "orders.product.created.queue";
    public static final String PRODUCT_CREATED_ROUTING_KEY = "product.created";

    // payment.* outcome events - published by billing-service once a
    // payment resolves to APPROVED/FAILED, consumed here to drive the same
    // state machine transitions OrderService.handlePaymentReceived/
    // handlePaymentFailed already implement (see ADR-0001, finding 5, and
    // ADR-0020's Roadmap follow-up)
    public static final String PAYMENT_APPROVED_QUEUE = "orders.payment.approved.queue";
    public static final String PAYMENT_APPROVED_ROUTING_KEY = "payment.approved";
    public static final String PAYMENT_FAILED_QUEUE = "orders.payment.failed.queue";
    public static final String PAYMENT_FAILED_ROUTING_KEY = "payment.failed";

    // Dead letter routing - every listener queue in this service
    // shares one DLX/DLQ pair; RepublishMessageRecoverer republishes using
    // the original received routing key, so the DLQ binds on "#" to catch
    // whichever queue's message was rejected after exhausting retries.
    public static final String DLX_EXCHANGE = "orders.dlx";
    public static final String DLQ = "orders.dlq";

    @Bean
    public TopicExchange authExchange() {
        return new TopicExchange(AUTH_EXCHANGE);
    }

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE);
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
    public Queue jwtCreatedProfileQueue() {
        return new Queue(JWT_CREATED_PROFILE_QUEUE, true);
    }

    @Bean
    public Queue inventoryReserveQueue() {
        return new Queue(INVENTORY_RESERVE_QUEUE, true);
    }

    @Bean
    public Queue stockReservedQueue() {
        return new Queue(STOCK_RESERVED_QUEUE, true);
    }

    @Bean
    public Queue stockInsufficientQueue() {
        return new Queue(STOCK_INSUFFICIENT_QUEUE, true);
    }

    @Bean
    public Queue paymentApprovedQueue() {
        return new Queue(PAYMENT_APPROVED_QUEUE, true);
    }

    @Bean
    public Queue paymentFailedQueue() {
        return new Queue(PAYMENT_FAILED_QUEUE, true);
    }

    @Bean
    public Queue productCreatedQueue() {
        return new Queue(PRODUCT_CREATED_QUEUE, true);
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
    public Binding jwtCreatedProfileBinding(Queue jwtCreatedProfileQueue, TopicExchange authExchange) {
        return BindingBuilder.bind(jwtCreatedProfileQueue)
                .to(authExchange)
                .with(JWT_ROUTING_KEY);
    }

    @Bean
    public Binding inventoryReserveBinding(Queue inventoryReserveQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(inventoryReserveQueue)
                .to(orderExchange)
                .with(RESERVE_STOCK_ROUTING_KEY);
    }

    @Bean
    public Binding stockReservedBinding(Queue stockReservedQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(stockReservedQueue)
                .to(orderExchange)
                .with(STOCK_RESERVED_ROUTING_KEY);
    }

    @Bean
    public Binding stockInsufficientBinding(Queue stockInsufficientQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(stockInsufficientQueue)
                .to(orderExchange)
                .with(STOCK_INSUFFICIENT_ROUTING_KEY);
    }

    @Bean
    public Binding paymentApprovedBinding(Queue paymentApprovedQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(paymentApprovedQueue)
                .to(orderExchange)
                .with(PAYMENT_APPROVED_ROUTING_KEY);
    }

    @Bean
    public Binding paymentFailedBinding(Queue paymentFailedQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(paymentFailedQueue)
                .to(orderExchange)
                .with(PAYMENT_FAILED_ROUTING_KEY);
    }

    @Bean
    public Binding productCreatedBinding(Queue productCreatedQueue, TopicExchange productExchange) {
        return BindingBuilder.bind(productCreatedQueue)
                .to(productExchange)
                .with(PRODUCT_CREATED_ROUTING_KEY);
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
        return factory;
    }
}