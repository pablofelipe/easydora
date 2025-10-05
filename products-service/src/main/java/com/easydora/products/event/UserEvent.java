package com.easydora.products.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UserEvent {
    private String eventType; // "USER_REGISTERED" ou "JWT_CREATED"
    private String userId;
    private String email;
    private String firstName;
    private String lastName;
    private String token;
    private String role;
    private Long expiresIn;
    private LocalDateTime timestamp;

    // Constructors
    public UserEvent() {}

    // Getters and Setters
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    
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