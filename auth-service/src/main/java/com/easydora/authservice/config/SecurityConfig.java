package com.easydora.authservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/ping", "/health", "/signup", "/login", "/verify-email", "/users/*/notification-profile").permitAll()
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
