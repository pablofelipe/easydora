package com.easydora.authservice.service;

import com.easydora.authservice.config.RabbitMQConfig;
import com.easydora.correlation.OutboxEnvelope;
import com.easydora.correlation.OutboxEnvelopeCodec;
import com.easydora.authservice.entity.OutboxEvent;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.entity.UserStatus;
import com.easydora.authservice.repository.OutboxEventRepository;
import com.easydora.authservice.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Happy-path counterpart to VerifyEmailOutboxIT: when save succeeds,
 * verifyEmail no longer publishes directly — it writes an OutboxEvent row
 * (captured here in place of a real Postgres insert) which
 * OutboxPublisher.publishPendingEvents then turns into a real message on a
 * real RabbitMQ (CI Phase 2 service container). Proves the event still
 * reaches a consumer, just one hop later than before.
 */
class VerifyEmailOutboxHappyPathIT {

    private static final String TEST_QUEUE = "test.user.verified.outbox.happy.queue";

    @Test
    void savedActivationEventuallyReachesTheConsumerViaTheOutboxPoller() throws Exception {
        String rabbitHost = System.getenv().getOrDefault("RABBITMQ_HOST", "localhost");
        int rabbitPort = Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672"));
        ConnectionFactory connectionFactory = new CachingConnectionFactory(rabbitHost, rabbitPort);
        ((CachingConnectionFactory) connectionFactory).setUsername("admin");
        ((CachingConnectionFactory) connectionFactory).setPassword(
                System.getenv().getOrDefault("RABBITMQ_PASSWORD", "local_dev_placeholder"));

        RabbitMQConfig config = new RabbitMQConfig();
        TopicExchange exchange = config.exchange();
        Queue testQueue = new Queue(TEST_QUEUE, true, false, false);
        Binding binding = BindingBuilder.bind(testQueue).to(exchange).with(RabbitMQConfig.USER_VERIFIED_KEY);

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.declareExchange(exchange);
        admin.declareQueue(testQueue);
        admin.declareBinding(binding);

        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(config.jsonMessageConverter());
        while (rabbitTemplate.receive(TEST_QUEUE, 200) != null) {
            // drain leftovers from earlier runs
        }

        RabbitMQProducerService rabbitMQProducerService = new RabbitMQProducerService(rabbitTemplate, exchange);

        User user = new User();
        user.setId(888L);
        user.setEmail("verified@example.com");
        user.setStatus(UserStatus.PENDING);
        user.setEmailVerificationToken("token-ok");

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByEmail("verified@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        VerificationTokenService verificationTokenService = mock(VerificationTokenService.class);
        when(verificationTokenService.validateVerificationToken("token-ok")).thenReturn(true);
        when(verificationTokenService.getEmailFromToken("token-ok")).thenReturn("verified@example.com");

        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        OutboxEventRepository userServiceOutboxRepository = mock(OutboxEventRepository.class);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

        UserService userService = new UserService(userRepository, passwordEncoder, rabbitMQProducerService, verificationTokenService, userServiceOutboxRepository);

        userService.verifyEmail("token-ok");

        verify(userServiceOutboxRepository).save(captor.capture());
        OutboxEvent savedEvent = captor.getValue();
        assertThat(savedEvent.getExchange()).isEqualTo(RabbitMQConfig.EXCHANGE_NAME);
        assertThat(savedEvent.getRoutingKey()).isEqualTo(RabbitMQConfig.USER_VERIFIED_KEY);

        OutboxEnvelope envelope = OutboxEnvelopeCodec.unwrap(savedEvent.getPayload());
        assertThat(envelope.body()).isEqualTo("888");

        assertThat(savedEvent.getPublishedAt()).isNull();

        OutboxEventRepository pollerOutboxRepository = mock(OutboxEventRepository.class);
        when(pollerOutboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(savedEvent));
        OutboxPublisher publisher = new OutboxPublisher(pollerOutboxRepository, rabbitTemplate);

        publisher.publishPendingEvents();

        assertThat(savedEvent.getPublishedAt()).isNotNull();
        verify(pollerOutboxRepository).save(savedEvent);

        var received = rabbitTemplate.receive(TEST_QUEUE, TimeUnit.SECONDS.toMillis(5));
        assertThat(received)
                .withFailMessage("consumer queue should have received the user.verified event via the outbox poller")
                .isNotNull();
        assertThat(new String(received.getBody())).isEqualTo("888");
    }
}
