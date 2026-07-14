package com.easydora.authservice.config;

import com.easydora.correlation.CorrelationIdFilter;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // The frontend calls this service exclusively through the Gateway.
    // Every permitAll path below already matches OPTIONS (a
    // requestMatchers pattern with no HttpMethod covers all methods), so
    // preflight wasn't actually blocked here the way it was in the other
    // three Spring services -- but without this bean no
    // Access-Control-Allow-Origin header was ever added, so the browser
    // still rejected the response.
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
            // Runs first, for every request regardless of the permitAll/
            // denyAll split below -- CorrelationId/RequestId must be in MDC
            // before any handler logs anything.
            .addFilterBefore(new CorrelationIdFilter(), UsernamePasswordAuthenticationFilter.class)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/ping", "/health", "/signup", "/login", "/verify-email", "/users/*/notification-profile", "/actuator/prometheus").permitAll()
                // auth-service has no protected endpoint of its own today --
                // it's the producer of the cross-service JWT broadcast, not
                // a consumer of it, so there is no authentication mechanism
                // to wire up here. denyAll() rejects anything outside the
                // permitAll list explicitly and unconditionally, instead of
                // authenticated(), which would silently depend on an auth
                // mechanism (e.g. .httpBasic()) that doesn't actually match
                // how this project protects anything else.
                .anyRequest().denyAll()
            )
            .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // Cost factor 12 for extra security
    }
}
