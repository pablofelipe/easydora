package com.easydora.authservice.service;

import com.easydora.authservice.dto.LoginResponse;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.entity.UserStatus;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuthService {

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
        
        // Validar senha
        if (!userService.validateUserCredentials(email, password)) {
            throw new RuntimeException("Invalid email or password");
        }
        
        // Validar status
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new RuntimeException("Account is not active");
        }
        
        // Gerar token e publicar evento
        String token = jwtService.generateToken(user);
        String userId = jwtService.extractUserId(token);
        String roles = jwtService.extractRoles(token);
        Long expiresIn = jwtService.getExpirationTime();
        
        rabbitMQProducerService.sendJwtCreatedEvent(token, userId, user.getEmail(), roles, expiresIn);
        
        // Criar resposta
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