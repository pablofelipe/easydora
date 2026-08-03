package com.easydora.authservice.service;

import com.easydora.authservice.config.RabbitMQConfig;
import com.easydora.correlation.OutboxEnvelope;
import com.easydora.correlation.OutboxEnvelopeCodec;
import com.easydora.authservice.entity.OutboxEvent;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.entity.UserStatus;
import com.easydora.authservice.repository.OutboxEventRepository;
import com.easydora.authservice.repository.UserRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Replaces VerifyEmailOutboxIT and VerifyEmailOutboxHappyPathIT (both
 * deleted): those proved the same behavior against a real RabbitMQ queue.
 * Neither Kafka nor RabbitMQ is referenced anywhere in this version — it
 * only asks whether an outbox row was recorded, which is the durable,
 * broker-independent proof that "user.verified will eventually be
 * published" (OutboxPublisherRetryTest already covers the poller draining
 * that row onto the broker).
 */
class VerifyEmailOutboxBehaviorTest {

    @Test
    void noOutboxEventIsRecordedWhenSaveFailsAfterActivation() {
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

        UserService userService = new UserService(userRepository, passwordEncoder,
                verificationTokenService, outboxEventRepository, new ObjectMapper(), io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);

        assertThatThrownBy(() -> userService.verifyEmail("token-flaky"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated DB failure");

        verify(outboxEventRepository, never())
                .save(any(OutboxEvent.class));
    }

    @Test
    void outboxEventIsRecordedWithCorrectFieldsWhenActivationSucceeds() {
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
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

        UserService userService = new UserService(userRepository, passwordEncoder,
                verificationTokenService, outboxEventRepository, new ObjectMapper(), io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);

        userService.verifyEmail("token-ok");

        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent savedEvent = captor.getValue();
        assertThat(savedEvent.getExchange()).isEqualTo(RabbitMQConfig.EXCHANGE_NAME);
        assertThat(savedEvent.getRoutingKey()).isEqualTo(RabbitMQConfig.USER_VERIFIED_KEY);

        OutboxEnvelope envelope = OutboxEnvelopeCodec.unwrap(savedEvent.getPayload());
        assertThat(envelope.body()).isEqualTo("888");
        assertThat(envelope.correlationId()).isNotBlank();
        assertThat(envelope.messageId()).isNotBlank();

        assertThat(savedEvent.getPublishedAt())
                .withFailMessage("a freshly recorded outbox row should not be marked published yet")
                .isNull();
    }
}
