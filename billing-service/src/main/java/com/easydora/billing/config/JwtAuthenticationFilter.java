package com.easydora.billing.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches the raw JWT string broadcast by auth-service's JwtCreatedEvent --
 * ported from products-service's version. billing-service has no
 * Buyer/Seller-style local entity, so JwtUserInfo has no "active" flag here,
 * unlike orders-service's variant.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, JwtUserInfo> validTokens = new ConcurrentHashMap<>();
    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final MeterRegistry meterRegistry;

    /** ObjectProvider (not a direct MeterRegistry dependency) so this bean
     * still constructs cleanly in a @WebMvcTest slice, which doesn't
     * autoconfigure a MeterRegistry bean -- falls back to a private,
     * unscraped SimpleMeterRegistry in that case; the real app always has
     * a MeterRegistry bean (Actuator + micrometer-registry-prometheus). */
    public JwtAuthenticationFilter(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            JwtUserInfo userInfo = validTokens.get(token);
            // Business metric (ADR-0036's Update): infra-level metrics
            // can't answer whether the two known, accepted residual risks
            // of this cache -- restart wiping it, and an entry outliving
            // its own JWT's expiresIn until read -- are actually happening
            // at runtime, as opposed to only in a unit test.
            String outcome;
            if (userInfo != null && userInfo.isExpired()) {
                // ADR-0039: a cache entry outlives its own JWT's expiresIn
                // otherwise, until this service happens to restart. Evict
                // it here so a later request for the same token doesn't
                // pay the same expiry check against a stale entry forever.
                validTokens.remove(token);
                userInfo = null;
                outcome = "expired";
            } else {
                outcome = userInfo != null ? "hit" : "miss";
            }
            meterRegistry.counter("jwt_cache_lookup_total", "outcome", outcome).increment();

            if (userInfo != null) {
                List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_" + userInfo.getRole())
                );

                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userInfo, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                // A miss/expired token does not terminate the request here
                // -- it logs and lets the chain continue unauthenticated,
                // the same fix products-service already had (ADR-0026).
                // Spring Security's own authorizeHttpRequests() in
                // SecurityConfig (anyRequest().authenticated()) is what
                // actually rejects this request further down the chain.
                logger.warn("Token NOT found in the valid tokens map");
            }
        } else {
            logger.warn("Authorization header missing or malformed");
        }

        filterChain.doFilter(request, response);
    }

    public void addValidToken(String token, JwtUserInfo userInfo) {
        validTokens.put(token, userInfo);
        logger.info("Token added for user: {}, Total tokens: {}", userInfo.getEmail(), validTokens.size());
    }

    public void removeToken(String token) {
        JwtUserInfo removed = validTokens.remove(token);
        if (removed != null) {
            logger.info("Token removed for user: {}, Total tokens: {}", removed.getEmail(), validTokens.size());
        }
    }

    public int getValidTokensSize() {
        return validTokens.size();
    }

    public boolean hasValidToken(String token) {
        return validTokens.containsKey(token);
    }

    public static class JwtUserInfo {
        private Long userId;
        private String email;
        private String firstName;
        private String lastName;
        private String role;
        private LocalDateTime expiresAt;

        /** Never expires -- used by every call site that predates ADR-0039
         * and by tests that construct a principal directly rather than
         * exercising the cache's TTL. */
        public JwtUserInfo(Long userId, String email, String firstName, String lastName, String role) {
            this(userId, email, firstName, lastName, role, LocalDateTime.MAX);
        }

        public JwtUserInfo(Long userId, String email, String firstName, String lastName, String role, LocalDateTime expiresAt) {
            this.userId = userId;
            this.email = email;
            this.firstName = firstName;
            this.lastName = lastName;
            this.role = role;
            this.expiresAt = expiresAt;
        }

        public Long getUserId() { return userId; }
        public String getEmail() { return email; }
        public String getFirstName() { return firstName; }
        public String getLastName() { return lastName; }
        public String getRole() { return role; }

        public boolean isExpired() {
            return LocalDateTime.now().isAfter(expiresAt);
        }
    }
}
