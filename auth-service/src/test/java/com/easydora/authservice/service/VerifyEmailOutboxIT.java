package com.easydora.authservice.service;

import com.easydora.authservice.config.RabbitMQConfig;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.entity.UserStatus;
import com.easydora.authservice.repository.OutboxEventRepository;
import com.easydora.authservice.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reproduces the Etapa 0 catalogued finding: UserService.verifyEmail
 * published user.verified over RabbitMQ before saving the activated user,
 * so a DB failure after the publish still let the event out even though
 * the corresponding state change was never persisted. Talks to a real
 * RabbitMQ (CI Phase 2 service container) via a dedicated test queue, the
 * same way JwtCreatedFanoutIT (orders-service) does. UserRepository is
 * mocked to inject the save failure; OutboxEventRepository is mocked too
 * (verifyEmail never reaches it, since save() throws first) — nothing here
 * asserts on it directly, the real proof is that no message arrives.
 */
class VerifyEmailOutboxIT {

    private static final String TEST_QUEUE = "test.user.verified.outbox.queue";

    @Test
    void noEventIsPublishedWhenSaveFailsAfterActivation() throws Exception {
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
        user.setId(777L);
        user.setEmail("flaky@example.com");
        user.setStatus(UserStatus.PENDING);
        user.setEmailVerificationToken("token-flaky");

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByEmail("flaky@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenThrow(new RuntimeException("simulated DB failure"));

        VerificationTokenService verificationTokenService = mock(VerificationTokenService.class);
        when(verificationTokenService.validateVerificationToken("token-flaky")).thenReturn(true);
        when(verificationTokenService.getEmailFromToken("token-flaky")).thenReturn("flaky@example.com");

        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);

        UserService userService = new UserService(userRepository, passwordEncoder, rabbitMQProducerService, verificationTokenService, outboxEventRepository);

        assertThatThrownBy(() -> userService.verifyEmail("token-flaky"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated DB failure");

        var received = rabbitTemplate.receive(TEST_QUEUE, TimeUnit.SECONDS.toMillis(3));

        assertThat(received)
                .withFailMessage("user.verified was published even though the user's activation was never persisted (save failed)")
                .isNull();
    }
}
