package com.easydora.orders.config;

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

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, JwtUserInfo> validTokens = new ConcurrentHashMap<>();
    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                  HttpServletResponse response, 
                                  FilterChain filterChain) throws ServletException, IOException {
        
        String authHeader = request.getHeader("Authorization");
        logger.info("Authorization Header: {}", authHeader);
        logger.info("Request URI: {}", request.getRequestURI());
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            logger.info("Token received: {}...", token.substring(0, Math.min(20, token.length())));
            logger.info("Stored tokens: {}", validTokens.size());

            JwtUserInfo userInfo = validTokens.get(token);

            if (userInfo != null) {
                logger.info("Valid token found for user: {}", userInfo.getEmail());
                List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_" + userInfo.getRole())
                );
                
                UsernamePasswordAuthenticationToken authentication = 
                    new UsernamePasswordAuthenticationToken(userInfo, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                
                SecurityContextHolder.getContext().setAuthentication(authentication);
                logger.info("Authentication set up for: {}", userInfo.getEmail());
            } else {
                logger.warn("Token NOT found in the valid tokens map");
                logger.warn("Full token: {}", token);
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

    // Method for debugging
    public void listTokens() {
        logger.info("Currently stored tokens:");
        validTokens.forEach((token, userInfo) -> {
            logger.info("  - Token: {}... -> User: {}",
                token.substring(0, Math.min(10, token.length())), 
                userInfo.getEmail());
        });
    }
    
    public int getValidTokensSize() {
        return validTokens.size();
    }
    
    public static class JwtUserInfo {
        private Long userId;
        private String email;
        private String firstName;
        private String lastName;
        private String role;
        private boolean active;


        public JwtUserInfo(Long  userId, String email, String firstName, String lastName, String role, boolean active) {
            this.userId = userId;
            this.email = email;
            this.firstName = firstName;
            this.lastName = lastName;
            this.role = role;
            this.active = active;
        }
        
        // getters
        public Long getUserId() { return userId; }
        public String getEmail() { return email; }
        public String getFirstName() { return firstName; }
        public String getLastName() { return lastName; }
        public String getRole() { return role; }
                
        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }
    }
}