package com.easydora.authservice.controller;

import com.easydora.authservice.dto.LoginRequest;
import com.easydora.authservice.dto.LoginResponse;
import com.easydora.authservice.dto.SignupRequest;
import com.easydora.authservice.dto.SignupResponse;
import com.easydora.authservice.entity.UserStatus;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.service.UserService;
import com.easydora.authservice.service.JwtService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    @Autowired
    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        Map<String, String> response = new HashMap<>();
        response.put("message", "Auth service is running!");
        response.put("status", "OK");
        response.put("schema", "auth_schema");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "OK");
        health.put("service", "auth-service");
        health.put("schema", "auth_schema");
        health.put("database", "Connected");
        
        return ResponseEntity.ok(health);
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest signupRequest) {
        try {
            SignupResponse response = userService.registerUser(signupRequest);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
            
        } catch (RuntimeException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Registration failed");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest loginRequest) {
        try {
            // Buscar usuário ativo pelo email
            Optional<User> userOpt = userService.findActiveUserByEmail(loginRequest.getEmail());
            
            if (userOpt.isEmpty()) {
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("error", "Login failed");
                errorResponse.put("message", "Invalid email or password");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
            }
            
            User user = userOpt.get();
            
            // Verificar senha
            if (!userService.validateUserCredentials(loginRequest.getEmail(), loginRequest.getPassword())) {
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("error", "Login failed");
                errorResponse.put("message", "Invalid email or password");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
            }
            
            // Verificar se usuário está ativo
            if (user.getStatus() != UserStatus.ACTIVE) {
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("error", "Login failed");
                errorResponse.put("message", "Account is not active");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
            }
            
            // Gerar token JWT
            String token = jwtService.generateToken(user);
            LocalDateTime expiresAt = user.getLastLoginAt(); // Você pode calcular baseado na expiração do JWT
            
            // Criar resposta
            LoginResponse response = new LoginResponse(
                token,
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole().name(),
                expiresAt
            );
            
            return ResponseEntity.ok(response);
            
        } catch (RuntimeException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Login failed");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestParam String token) {
        try {
            userService.verifyEmail(token);
            
            Map<String, String> response = new HashMap<>();
            response.put("message", "Email verified successfully");
            response.put("status", "ACTIVE");
            return ResponseEntity.ok(response);
            
        } catch (RuntimeException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Email verification failed");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }
}