package com.easydora.authservice.dto;

import java.time.LocalDateTime;

public class JwtCreatedEvent {
    private String token;
    // ADR-0002's Update: was String -- the JWT subject claim, unconverted
    // -- while every consumer (products/orders/billing/notification-service)
    // already treated it as numeric and only worked by Jackson/Python's
    // implicit string-to-number coercion. Fixed to match the type every
    // consumer already assumes.
    private Long userId;
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    private LocalDateTime createdAt;
    private Long expiresIn;

    // Construtores
    public JwtCreatedEvent() {}

    public JwtCreatedEvent(String token, Long userId, String email, String firstName, String lastName, String role, LocalDateTime createdAt, Long expiresIn) {
        this.token = token;
        this.userId = userId;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.role = role;
        this.createdAt = createdAt;
        this.expiresIn = expiresIn;
    }

    // Getters e Setters
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    
    public String getRole() { return role; }
    public void setRole(String roles) { this.role = roles; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public Long getExpiresIn() { return expiresIn; }
    public void setExpiresIn(Long expiresIn) { this.expiresIn = expiresIn; }
}