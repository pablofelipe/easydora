package com.easydora.authservice.dto;

import java.time.LocalDateTime;

public class JwtCreatedEvent {
    private String token;
    private String userId;
    private String email;
    private String firstName;
    private String lastName;
    private String roles;
    private LocalDateTime createdAt;
    private Long expiresIn;

    // Construtores
    public JwtCreatedEvent() {}

    public JwtCreatedEvent(String token, String userId, String email, String firstName, String lastName, String roles, LocalDateTime createdAt, Long expiresIn) {
        this.token = token;
        this.userId = userId;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.roles = roles;
        this.createdAt = createdAt;
        this.expiresIn = expiresIn;
    }

    // Getters e Setters
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    
    public String getRoles() { return roles; }
    public void setRoles(String roles) { this.roles = roles; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public Long getExpiresIn() { return expiresIn; }
    public void setExpiresIn(Long expiresIn) { this.expiresIn = expiresIn; }
}