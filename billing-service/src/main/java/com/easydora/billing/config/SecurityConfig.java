package com.easydora.billing.config;

import com.easydora.correlation.CorrelationIdFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    // The frontend calls this service exclusively through the Gateway
    // with an Authorization header, which makes every request a
    // non-simple CORS request -- the browser sends a preflight OPTIONS
    // first, mirroring the same fix applied to auth/products/orders-service.
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);
        // Response headers are NOT readable by browser JS unless explicitly
        // exposed, regardless of allowedHeaders (which only governs the
        // *request* side of a preflight) -- without this, fetch()'s
        // response.headers.get('X-Correlation-Id') always returns null in a
        // real browser even though curl (not subject to CORS) sees it fine.
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "X-Request-Id"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(authz -> authz
                // CORS preflight carries no Authorization header by design;
                // it must be let through before the authenticated() rule
                // below or the browser never gets to send the real request.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/ping", "/health", "/error", "/actuator/prometheus").permitAll()
                .anyRequest().authenticated()
            )
            .csrf(csrf -> csrf.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            // JwtAuthenticationFilter must be registered first so Spring
            // Security knows its position before CorrelationIdFilter is
            // anchored relative to it below.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new CorrelationIdFilter(), JwtAuthenticationFilter.class);

        return http.build();
    }
}
