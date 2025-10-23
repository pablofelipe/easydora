package com.easydora.orders.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UserEvent {
    @JsonProperty("eventType")
    private String eventType; // "USER_REGISTERED" ou "JWT_CREATED"
    @JsonProperty("userId")
    private Long userId;
    @JsonProperty("email")
    private String email;
    @JsonProperty("firstName")
    private String firstName;
    @JsonProperty("lastName")
    private String lastName;
    @JsonProperty("verificationToken")
    private String verificationToken;
    @JsonProperty("token")
    private String token;
    @JsonProperty("role")
    private String role;
    @JsonProperty("expiresIn")
    private Long expiresIn;
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
    
    private LocalDateTime timestamp = LocalDateTime.now();

    // Constructors
    public UserEvent() {}

    // Getters and Setters
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }
    
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    
    public Long getExpiresIn() { return expiresIn; }
    public void setExpiresIn(Long expiresIn) { this.expiresIn = expiresIn; }
    
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    // Helper methods
    public boolean isSeller() {
        return "SELLER".equalsIgnoreCase(role);
    }
    
    public boolean isBuyer() {
        return "BUYER".equalsIgnoreCase(role);
    }
    
    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
    

    public String getFullName() {
        if (firstName != null && lastName != null) {
            return firstName + " " + lastName;
        }
        if (firstName != null) {
            return firstName;
        }
        if (lastName != null) {
            return lastName;
        }
        return "User"; // Fallback
    }

    public boolean hasNameInfo() {
        return (firstName != null && !firstName.trim().isEmpty()) || 
               (lastName != null && !lastName.trim().isEmpty());
    }
}