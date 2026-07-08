package com.easydora.authservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
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
                .anyRequest().authenticated()
            )
            .csrf(csrf -> csrf.disable())
            // Building a custom SecurityFilterChain bean opts out of Spring
            // Boot's automatic HTTP Basic setup -- without this, there is no
            // authentication mechanism wired up at all, so every request
            // past the permitAll list gets a blanket 403 regardless of
            // whether credentials are present, correct, or wrong.
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // Cost factor 12 for extra security
    }
}
