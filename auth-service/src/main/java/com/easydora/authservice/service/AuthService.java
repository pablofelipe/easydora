package com.easydora.authservice.service;

import com.easydora.correlation.BusinessEventLog;
import com.easydora.authservice.dto.LoginResponse;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.entity.UserStatus;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final JwtService jwtService;
    private final RabbitMQProducerService rabbitMQProducerService;
    private final UserService userService;

    public AuthService(JwtService jwtService, RabbitMQProducerService rabbitMQProducerService, UserService userService) {
        this.jwtService = jwtService;
        this.rabbitMQProducerService = rabbitMQProducerService;
        this.userService = userService;
    }

    public LoginResponse authenticateUser(String email, String password) {
        
        User user = userService.findActiveUserByEmail(email)
            .orElseThrow(() -> new RuntimeException("Invalid email or password"));
        
        // Validate password
        if (!userService.validateUserCredentials(email, password)) {
            throw new RuntimeException("Invalid email or password");
        }

        // Validate status
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new RuntimeException("Account is not active");
        }

        // Generate token and publish event
        String token = jwtService.generateToken(user);
        String userId = jwtService.extractUserId(token);
        String role = jwtService.extractRoles(token);
        Long expiresIn = jwtService.getExpirationTime();
        
        rabbitMQProducerService.sendJwtCreatedEvent(token, Long.parseLong(userId), user.getEmail(), user.getFirstName(),user.getLastName(), role, expiresIn);
        BusinessEventLog.info(logger, "jwt.created.published", user.getId(), "JWT created event published");

        // Create response
        return new LoginResponse(
            token,
            user.getId(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.getRole().name(),
            LocalDateTime.now().plusSeconds(expiresIn / 1000)
        );
    }
    
    public Long getTokenExpirationTime() {
        return jwtService.getExpirationTime();
    }
}