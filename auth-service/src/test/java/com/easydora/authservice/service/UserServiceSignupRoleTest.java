package com.easydora.authservice.service;

import com.easydora.authservice.dto.SignupRequest;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.repository.OutboxEventRepository;
import com.easydora.authservice.repository.UserRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The public /signup endpoint used to accept any UserRole.valueOf()-able
 * string, including "ADMIN" -- self-service admin registration by an
 * anonymous caller. Harmless while no endpoint anywhere checked the ADMIN
 * role, but a real privilege-escalation path once orders-service's
 * fulfillment actions started gating on it.
 */
class UserServiceSignupRoleTest {

    private UserService newUserService(UserRepository userRepository) {
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        VerificationTokenService verificationTokenService = mock(VerificationTokenService.class);
        when(verificationTokenService.generateEmailVerificationToken(any())).thenReturn("token");
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return new UserService(userRepository, passwordEncoder,
                verificationTokenService, outboxEventRepository, objectMapper, io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);
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
    void signupWithAdminRoleIsRejected() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmail("someone@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserService userService = newUserService(userRepository);

        assertThatThrownBy(() -> userService.registerUser(requestWithRole("ADMIN")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signupWithLowercaseAdminRoleIsAlsoRejected() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmail("someone@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserService userService = newUserService(userRepository);

        assertThatThrownBy(() -> userService.registerUser(requestWithRole("admin")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signupWithUnknownRoleStringIsRejected() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmail("someone@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserService userService = newUserService(userRepository);

        assertThatThrownBy(() -> userService.registerUser(requestWithRole("SUPERUSER")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signupWithBuyerRoleStillSucceeds() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmail("someone@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserService userService = newUserService(userRepository);

        userService.registerUser(requestWithRole("BUYER"));
        userService.registerUser(requestWithRole("buyer"));
    }

    @Test
    void signupWithSellerRoleStillSucceeds() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmail("someone@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserService userService = newUserService(userRepository);

        userService.registerUser(requestWithRole("SELLER"));
    }
}
