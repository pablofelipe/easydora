package com.easydora.orders.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the fix for the JwtConsumer / UserEventsConsumer competing-consumer
 * bug: both used to listen on the same physical queue
 * (orders.jwt.created.queue), so RabbitMQ round-robinned each jwt.created
 * message to only one of the two, silently dropping the other's share.
 * They now listen on two distinct queues (orders.jwt.created.queue and
 * orders.jwt.created.profile.queue), both bound to the same exchange and
 * routing key — so a single publish must be fanned out as one copy to each,
 * not round-robinned between them. This test talks to a real RabbitMQ
 * (docker-compose) and drives the actual @Configuration bean declarations
 * in RabbitMQConfig, not a re-declared topology.
 */
class JwtCreatedFanoutIntegrationTest {

    @Test
    void oneJwtCreatedPublishIsDeliveredToBothQueuesIndependently() throws Exception {
        ConnectionFactory connectionFactory = new CachingConnectionFactory("localhost", 5672);
        ((CachingConnectionFactory) connectionFactory).setUsername("admin");
        ((CachingConnectionFactory) connectionFactory).setPassword("87j9d]#2@5B5");

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        RabbitMQConfig config = new RabbitMQConfig();

        admin.declareExchange(config.authExchange());
        admin.declareQueue(config.jwtCreatedQueue());
        admin.declareQueue(config.jwtCreatedProfileQueue());
        admin.declareBinding(config.jwtCreatedBinding(config.jwtCreatedQueue(), config.authExchange()));
        admin.declareBinding(config.jwtCreatedProfileBinding(config.jwtCreatedProfileQueue(), config.authExchange()));

        // Drain both queues first so leftovers from earlier runs don't get
        // mistaken for this test's message.
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        while (template.receive(RabbitMQConfig.JWT_CREATED_QUEUE, 200) != null) {
            // drain
        }
        while (template.receive(RabbitMQConfig.JWT_CREATED_PROFILE_QUEUE, 200) != null) {
            // drain
        }

        String body = "{\"token\":\"tok-1\",\"userId\":\"1\",\"email\":\"buyer@example.com\",\"role\":\"BUYER\"}";
        template.send(RabbitMQConfig.AUTH_EXCHANGE, RabbitMQConfig.JWT_ROUTING_KEY,
                new org.springframework.amqp.core.Message(body.getBytes(), new MessageProperties()));

        var fromSessionQueue = template.receive(RabbitMQConfig.JWT_CREATED_QUEUE, TimeUnit.SECONDS.toMillis(5));
        var fromProfileQueue = template.receive(RabbitMQConfig.JWT_CREATED_PROFILE_QUEUE, TimeUnit.SECONDS.toMillis(5));

        assertThat(fromSessionQueue)
                .withFailMessage("JwtConsumer's queue (%s) should have received its own copy of the message", RabbitMQConfig.JWT_CREATED_QUEUE)
                .isNotNull();
        assertThat(fromProfileQueue)
                .withFailMessage("UserEventsConsumer's queue (%s) should have received its own copy of the message", RabbitMQConfig.JWT_CREATED_PROFILE_QUEUE)
                .isNotNull();
        assertThat(new String(fromSessionQueue.getBody())).isEqualTo(body);
        assertThat(new String(fromProfileQueue.getBody())).isEqualTo(body);
    }
}
