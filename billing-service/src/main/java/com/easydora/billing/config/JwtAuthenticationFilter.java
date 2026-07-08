package com.easydora.billing.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

import java.io.IOException;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            JwtUserInfo userInfo = validTokens.get(token);

            if (userInfo != null) {
                List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_" + userInfo.getRole())
                );

                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userInfo, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                logger.warn("Token NOT found in the valid tokens map");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("{\"error\": \"Invalid or expired token\"}");
                return;
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

        public JwtUserInfo(Long userId, String email, String firstName, String lastName, String role) {
            this.userId = userId;
            this.email = email;
            this.firstName = firstName;
            this.lastName = lastName;
            this.role = role;
        }

        public Long getUserId() { return userId; }
        public String getEmail() { return email; }
        public String getFirstName() { return firstName; }
        public String getLastName() { return lastName; }
        public String getRole() { return role; }
    }
}
