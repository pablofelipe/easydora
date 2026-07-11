package com.easydora.authservice.config;

import com.easydora.authservice.entity.User;
import com.easydora.authservice.entity.UserRole;
import com.easydora.authservice.entity.UserStatus;
import com.easydora.authservice.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Bootstraps the platform-operations account (ADR-0027's Update: ADMIN,
 * checked by orders-service's fulfillment endpoints) from ADMIN_EMAIL/
 * ADMIN_PASSWORD environment variables instead of a committed
 * Flyway seed -- no credential, hashed or plaintext, ever lands in
 * version control. Idempotent (checks existsByEmail first) and inert by
 * default: an environment that never sets these two variables simply gets
 * no admin account, with a log line, not a startup failure.
 */
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminAccountInitializer(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${ADMIN_EMAIL:}") String adminEmail,
            @Value("${ADMIN_PASSWORD:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            logger.info("ADMIN_EMAIL/ADMIN_PASSWORD not set -- skipping operations-account bootstrap");
            return;
        }

        String normalizedEmail = adminEmail.toLowerCase().trim();
        if (userRepository.existsByEmail(normalizedEmail)) {
            return;
        }

        User admin = new User();
        admin.setEmail(normalizedEmail);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setFirstName("Platform");
        admin.setLastName("Operations");
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setEmailVerified(true);

        userRepository.save(admin);
        logger.info("Bootstrapped operations account: {}", normalizedEmail);
    }
}
