package com.easydora.authservice.service;

import com.easydora.authservice.config.JwtProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Proves the app.jwt.secret dev-profile fallback used whenever no
 * APP_JWT_SECRET env var overrides it (application-dev.properties)
 * satisfies HMAC-SHA's minimum key length (RFC 7518 SS3.2, >= 256 bits).
 * ADR-0013 found the previous placeholder ("local_dev_placeholder", 168
 * bits) threw WeakKeyException from both JwtService's and
 * VerificationTokenService's constructors the moment auth-service booted
 * without docker-compose or a local .env overriding it -- every existing
 * deployment path happened to override it, so this went unnoticed.
 */
class JwtSecretStrengthTest {

    // Mirrors application-dev.properties's app.jwt.secret fallback
    // exactly -- kept in sync manually since this lightweight unit test
    // does not exercise Spring's own property resolution.
    private static final String DEV_DEFAULT_SECRET = "local_dev_placeholder_not_for_production_use";

    @Test
    void devDefaultSecretMeetsHmacShaMinimumKeyLength() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(DEV_DEFAULT_SECRET);

        assertDoesNotThrow(() -> new JwtService(properties),
            "JwtService should construct without throwing WeakKeyException");
        assertDoesNotThrow(() -> new VerificationTokenService(properties),
            "VerificationTokenService should construct without throwing WeakKeyException");
    }
}
