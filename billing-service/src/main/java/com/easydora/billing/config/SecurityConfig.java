package com.easydora.billing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/ping", "/health", "/error").permitAll()
                .anyRequest().authenticated()
            )
            // Building a custom SecurityFilterChain bean opts out of Spring
            // Boot's automatic HTTP Basic setup -- without this, there is no
            // authentication mechanism wired up at all, so every request
            // past the permitAll list gets a blanket 403 regardless of
            // whether credentials are present, correct, or wrong.
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}
