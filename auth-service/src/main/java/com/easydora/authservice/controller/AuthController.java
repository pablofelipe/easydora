package com.easydora.authservice.controller;

import com.easydora.authservice.dto.LoginRequest;
import com.easydora.authservice.dto.LoginResponse;
import com.easydora.authservice.dto.SignupRequest;
import com.easydora.authservice.dto.SignupResponse;
import com.easydora.authservice.service.UserService;
import com.easydora.authservice.service.AuthService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {

    private final UserService userService;
    private final AuthService authService;
    private final DataSource dataSource;

    @Autowired
    public AuthController(UserService userService, AuthService authService, DataSource dataSource) {
        this.userService = userService;
        this.authService = authService;
        this.dataSource = dataSource;
    }

    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        Map<String, String> response = new HashMap<>();
        response.put("message", "Auth service is running!");
        response.put("status", "OK");
        response.put("schema", "auth_schema");
        return ResponseEntity.ok(response);
    }

    // A short-timeout, real connectivity probe (see ADR-0010's residual
    // gap: this endpoint -- the one Docker's own HEALTHCHECK and the
    // Gateway route hit -- used to hardcode a claim about the database
    // without ever checking it). 2s is generous against this project's own
    // measured healthy-backend latencies (100-115ms, ADR-0006) while still
    // bounding how long a caller waits on a genuinely stuck connection.
    private boolean isDatabaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean databaseReachable = isDatabaseReachable();

        Map<String, Object> health = new HashMap<>();
        health.put("status", databaseReachable ? "OK" : "DOWN");
        health.put("service", "auth-service");
        health.put("schema", "auth_schema");
        health.put("database", databaseReachable ? "Connected" : "Disconnected");

        return databaseReachable
            ? ResponseEntity.ok(health)
            : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(health);
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
            LoginResponse response = authService.authenticateUser(
                loginRequest.getEmail(), 
                loginRequest.getPassword()
            );
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Login failed");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
        }
    }

    @GetMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestParam String token) {
        try {
            String decodedToken = URLDecoder.decode(token, StandardCharsets.UTF_8);

            userService.verifyEmail(decodedToken);
            
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