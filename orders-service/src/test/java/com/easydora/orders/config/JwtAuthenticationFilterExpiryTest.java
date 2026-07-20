package com.easydora.orders.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves ADR-0039's expiresAt TTL takes effect at read time in
 * orders-service, mirroring billing-service's/products-service's own
 * JwtAuthenticationFilterExpiryTest. Also proves the
 * jwt_cache_lookup_total{outcome} metric (ADR-0036's Update) distinguishes
 * the three outcomes, and that a miss/expired token never terminates the
 * request itself -- it logs and lets the chain continue, the same
 * consistency fix products-service already had (ADR-0026); Spring
 * Security's own authorizeHttpRequests(), not this filter, is what decides
 * permitAll vs 401 for whatever comes next in the chain.
 */
class JwtAuthenticationFilterExpiryTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> objectProvider(MeterRegistry meterRegistry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(meterRegistry);
        return provider;
    }

    @Test
    void anExpiredCacheEntryIsNotAuthenticatedAndIsEvicted() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(objectProvider(meterRegistry));
        JwtAuthenticationFilter.JwtUserInfo expired = new JwtAuthenticationFilter.JwtUserInfo(
                1L, "buyer@example.com", "Ana", "Silva", "BUYER", true, LocalDateTime.now().minusSeconds(1));
        filter.addValidToken("expired-token", expired);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer expired-token");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(filter.hasValidToken("expired-token"))
                .as("an expired entry must be evicted from the map, not just skipped this once")
                .isFalse();
        assertThat(meterRegistry.get("jwt_cache_lookup_total").tag("outcome", "expired").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aNotYetExpiredCacheEntryStillAuthenticates() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(objectProvider(meterRegistry));
        JwtAuthenticationFilter.JwtUserInfo stillValid = new JwtAuthenticationFilter.JwtUserInfo(
                1L, "buyer@example.com", "Ana", "Silva", "BUYER", true, LocalDateTime.now().plusMinutes(5));
        filter.addValidToken("valid-token", stillValid);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(meterRegistry.get("jwt_cache_lookup_total").tag("outcome", "hit").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aTokenNeverCachedIsUnauthenticated_theRestartScenario() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(objectProvider(meterRegistry));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer a-token-issued-before-restart");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(meterRegistry.get("jwt_cache_lookup_total").tag("outcome", "miss").counter().count())
                .isEqualTo(1.0);
    }
}
