package com.easydora.authservice.event.listener;

import com.easydora.authservice.event.UserRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class UserRegisteredEventListener {
    
    private static final Logger logger = LoggerFactory.getLogger(UserRegisteredEventListener.class);
    
    @EventListener
    public void handleUserRegistered(UserRegisteredEvent event) {
        // Just for testing purposes for now
        String verificationLink = String.format(
            "http://localhost:8081/auth/verify-email?token=%s",
            event.getVerificationToken()
        );

        logger.info("\n" +
            "=== NEW USER REGISTERED ===\n" +
            "User: {} {} ({})\n" +
            "User ID: {}\n" +
            "Verification Link: {}\n" +
            "Token created at: {}\n" +
            "===================================",
            event.getFirstName(),
            event.getLastName(), 
            event.getEmail(),
            event.getUserId(),
            verificationLink,
            event.getCreatedAt()
        );
        
        logVerificationCurlCommand(event.getVerificationToken());
    }
    
    private void logVerificationCurlCommand(String token) {
        String curlCommand = String.format(
            "curl -X GET \"http://localhost:8081/verify-email?token=%s\"",
            token
        );
        
        logger.info("\n" +
            "TEST CURL COMMAND:\n" +
            "{}\n" +
            "-----------------------------",
            curlCommand
        );
    }
}