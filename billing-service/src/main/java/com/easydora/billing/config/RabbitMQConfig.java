package com.easydora.billing.config;

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

    // Exchange do orders-service
    public static final String ORDER_EXCHANGE = "order.exchange";

    // Routing keys para consumir
    public static final String ORDER_CREATED_KEY = "order.created";

    // Queues do billing-service
    public static final String ORDER_CREATED_QUEUE = "billing.order.created.queue";

    // Routing keys para publicar o resultado do processamento do pagamento
    // (consumidos por orders-service - ver PaymentEventsConsumer). Publicados
    // na mesma order.exchange já declarada acima, sem exchange/fila novas
    // aqui, já que este serviço só produz esses dois eventos.
    public static final String PAYMENT_APPROVED_KEY = "payment.approved";
    public static final String PAYMENT_FAILED_KEY = "payment.failed";

    // Payment compensation (ADR-0034): RefundPaymentCommand is a command
    // published by orders-service, not a fact-event -- consumed here via a
    // dedicated queue. The two outcomes below are published back on the
    // same order.exchange, no new queue needed (this service is the
    // producer, not a consumer, of those routing keys).
    public static final String REFUND_PAYMENT_REQUESTED_QUEUE = "billing.payment.refund.requested.queue";
    public static final String REFUND_PAYMENT_REQUESTED_KEY = "payment.refund.requested";
    public static final String PAYMENT_REFUNDED_KEY = "payment.refunded";
    public static final String PAYMENT_REFUND_FAILED_KEY = "payment.refund.failed";

    // Exchange do auth-service (broadcast de JwtCreatedEvent)
    public static final String AUTH_EXCHANGE = "auth.exchange";

    public static final String JWT_ROUTING_KEY = "jwt.created";

    public static final String JWT_CREATED_QUEUE = "billing.jwt.created.queue";

    // Dead letter routing - every listener queue in this service
    // shares one DLX/DLQ pair; RepublishMessageRecoverer republishes using
    // the original received routing key, so the DLQ binds on "#" to catch
    // whichever queue's message was rejected after exhausting retries.
    public static final String DLX_EXCHANGE = "billing.dlx";
    public static final String DLQ = "billing.dlq";

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE);
    }

    @Bean
    public Queue orderCreatedQueue() {
        return new Queue(ORDER_CREATED_QUEUE, true);
    }

    @Bean
    public Binding orderCreatedBinding(Queue orderCreatedQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(orderCreatedQueue)
                .to(orderExchange)
                .with(ORDER_CREATED_KEY);
    }

    @Bean
    public Queue refundPaymentRequestedQueue() {
        return new Queue(REFUND_PAYMENT_REQUESTED_QUEUE, true);
    }

    @Bean
    public Binding refundPaymentRequestedBinding(Queue refundPaymentRequestedQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(refundPaymentRequestedQueue)
                .to(orderExchange)
                .with(REFUND_PAYMENT_REQUESTED_KEY);
    }

    @Bean
    public TopicExchange authExchange() {
        return new TopicExchange(AUTH_EXCHANGE);
    }

    @Bean
    public Queue jwtCreatedQueue() {
        return new Queue(JWT_CREATED_QUEUE, true);
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

    // Shared with PaymentService's Outbox writes (ADR-0037): an outbox
    // row's payload is stored as plain JSON text and later sent as raw
    // bytes by OutboxPublisher, with no message converter involved at
    // publish time -- so the text written here must already match exactly
    // what this same ObjectMapper would have produced, which is why
    // PaymentService is wired to this exact bean instead of building its
    // own.
    @Bean
    public ObjectMapper outboxObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return objectMapper;
    }

    @Bean
    public MessageConverter messageConverter(ObjectMapper outboxObjectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(outboxObjectMapper);

        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(DefaultJackson2JavaTypeMapper.TypePrecedence.INFERRED);
        converter.setJavaTypeMapper(typeMapper);

        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        return rabbitTemplate;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        // Applies spring.rabbitmq.listener.simple.retry.* (limited attempts,
        // exponential backoff) and wires the messageRecoverer bean above as
        // the recoverer used once retries are exhausted - no custom retry
        // code, just Spring Boot's native listener container configurer.
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        return factory;
    }
}