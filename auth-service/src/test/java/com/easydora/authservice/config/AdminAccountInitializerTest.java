package com.easydora.authservice.config;

import com.easydora.authservice.entity.User;
import com.easydora.authservice.entity.UserRole;
import com.easydora.authservice.entity.UserStatus;
import com.easydora.authservice.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The operations account (the ADMIN role, checked by orders-service's
 * fulfillment endpoints) is bootstrapped from
 * ADMIN_EMAIL/ADMIN_PASSWORD environment variables at startup instead of
 * a committed migration -- no credential (hash or plaintext) ever lands
 * in version control. Idempotent: only creates the row when it's both
 * configured and not already present, and never crashes the app when the
 * env vars are absent (e.g. any environment that doesn't need an admin).
 */
class AdminAccountInitializerTest {

    private static final ApplicationArguments NO_ARGS = mock(ApplicationArguments.class);

    @Test
    void doesNothingWhenEnvVarsAreBlank() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        AdminAccountInitializer initializer = new AdminAccountInitializer(userRepository, passwordEncoder, "", "");
        initializer.run(NO_ARGS);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void doesNothingWhenOnlyEmailIsConfigured() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        AdminAccountInitializer initializer =
                new AdminAccountInitializer(userRepository, passwordEncoder, "ops@easydora.com", "");
        initializer.run(NO_ARGS);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void doesNothingWhenTheAccountAlreadyExists() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmail("ops@easydora.com")).thenReturn(true);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        AdminAccountInitializer initializer =
                new AdminAccountInitializer(userRepository, passwordEncoder, "ops@easydora.com", "S3cret!");
        initializer.run(NO_ARGS);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void createsAnActiveAdminAccountWhenConfiguredAndAbsent() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmail("ops@easydora.com")).thenReturn(false);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode("S3cret!")).thenReturn("hashed-secret");

        AdminAccountInitializer initializer =
                new AdminAccountInitializer(userRepository, passwordEncoder, "ops@easydora.com", "S3cret!");
        initializer.run(NO_ARGS);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        User created = captor.getValue();
        assertThat(created.getEmail()).isEqualTo("ops@easydora.com");
        assertThat(created.getPasswordHash()).isEqualTo("hashed-secret");
        assertThat(created.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.getEmailVerified()).isTrue();
    }
}
