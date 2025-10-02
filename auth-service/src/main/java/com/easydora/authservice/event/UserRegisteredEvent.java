package com.easydora.authservice.event;

import java.time.LocalDateTime;

public class UserRegisteredEvent {
    private Long userId;
    private String email;
    private String firstName;
    private String lastName;
    private String verificationToken;
    private LocalDateTime createdAt;
    
    public UserRegisteredEvent() {}
    
    public UserRegisteredEvent(Long userId, String email, String firstName, 
                             String lastName, String verificationToken) {
        this.userId = userId;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.verificationToken = verificationToken;
        this.createdAt = LocalDateTime.now();
    }
    
    // Getters
    public Long getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getVerificationToken() { return verificationToken; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    
    @Override
    public String toString() {
        return "UserRegisteredEvent{" +
                "userId=" + userId +
                ", email='" + email + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", verificationToken='[HIDDEN]'" +
                ", createdAt=" + createdAt +
                '}';
    }
}
