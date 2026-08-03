package com.easydora.authservice.service;

import com.easydora.authservice.config.RabbitMQConfig;
import com.easydora.authservice.dto.SignupRequest;
import com.easydora.authservice.entity.OutboxEvent;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.event.UserRegisteredEvent;
import com.easydora.authservice.repository.OutboxEventRepository;
import com.easydora.authservice.repository.UserRepository;
import com.easydora.correlation.OutboxEnvelope;
import com.easydora.correlation.OutboxEnvelopeCodec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirrors VerifyEmailOutboxBehaviorTest (ADR-0003) for registerUser's own
 * gap: it used to publish user.registered directly via
 * RabbitMQProducerService right after userRepository.save(user) succeeded,
 * so a publish failure after a successful save could silently drop the
 * event (ADR-0003 explicitly left this call site unfixed, catalogued as
 * residual debt). registerUser now writes an OutboxEvent row in the same
 * transaction as the save instead -- OutboxPublisher.publishPendingEvents
 * (already regression-tested by OutboxPublisherRetryTest) is what actually
 * reaches the broker, at-least-once. UserService no longer depends on
 * RabbitMQProducerService at all, since verifyEmail moved to the outbox
 * first (ADR-0003) and registerUser is the last caller this closes.
 */
class RegisterUserOutboxBehaviorTest {

    private static ObjectMapper newObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private SignupRequest requestWithRole(String role) {
        SignupRequest request = new SignupRequest();
        request.setEmail("someone@example.com");
        request.setPassword("password123");
        request.setFirstName("Someone");
        request.setLastName("Anonymous");
        request.setRole(role);
        return request;
    }

    @Test
    void noOutboxEventIsRecordedWhenSaveFailsAfterRegistration() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmail("someone@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenThrow(new RuntimeException("simulated DB failure"));

        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        VerificationTokenService verificationTokenService = mock(VerificationTokenService.class);
        when(verificationTokenService.generateEmailVerificationToken(any())).thenReturn("token");
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);

        UserService userService = new UserService(userRepository, passwordEncoder,
                verificationTokenService, outboxEventRepository, newObjectMapper(), io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);

        assertThatThrownBy(() -> userService.registerUser(requestWithRole("BUYER")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated DB failure");

        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void outboxEventIsRecordedWithCorrectFieldsWhenRegistrationSucceeds() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmail("someone@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(42L);
            return u;
        });

        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        VerificationTokenService verificationTokenService = mock(VerificationTokenService.class);
        when(verificationTokenService.generateEmailVerificationToken(any())).thenReturn("token-ok");
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

        UserService userService = new UserService(userRepository, passwordEncoder,
                verificationTokenService, outboxEventRepository, newObjectMapper(), io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);

        userService.registerUser(requestWithRole("BUYER"));

        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent savedEvent = captor.getValue();
        assertThat(savedEvent.getExchange()).isEqualTo(RabbitMQConfig.EXCHANGE_NAME);
        assertThat(savedEvent.getRoutingKey()).isEqualTo(RabbitMQConfig.USER_REGISTERED_KEY);
        assertThat(savedEvent.getPublishedAt())
                .withFailMessage("a freshly recorded outbox row should not be marked published yet")
                .isNull();

        OutboxEnvelope envelope = OutboxEnvelopeCodec.unwrap(savedEvent.getPayload());
        assertThat(envelope.correlationId()).isNotBlank();
        assertThat(envelope.messageId()).isNotBlank();

        UserRegisteredEvent event = newObjectMapper().readValue(envelope.body(), UserRegisteredEvent.class);
        assertThat(event.getUserId()).isEqualTo(42L);
        assertThat(event.getEmail()).isEqualTo("someone@example.com");
        assertThat(event.getFirstName()).isEqualTo("Someone");
        assertThat(event.getLastName()).isEqualTo("Anonymous");
        assertThat(event.getRole()).isEqualTo("BUYER");
        assertThat(event.getVerificationToken()).isEqualTo("token-ok");
    }
}
