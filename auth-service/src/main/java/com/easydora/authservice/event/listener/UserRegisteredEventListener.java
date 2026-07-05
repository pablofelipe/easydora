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
        // Apenas para testes por enquanto
        String verificationLink = String.format(
            "http://localhost:8081/auth/verify-email?token=%s", 
            event.getVerificationToken()
        );
        
        logger.info("\n" +
            "=== NOVO USUÁRIO REGISTRADO ===\n" +
            "Usuário: {} {} ({})\n" +
            "User ID: {}\n" +
            "Link de Verificação: {}\n" +
            "Token criado em: {}\n" +
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
            "COMANDO CURL PARA TESTE:\n" +
            "{}\n" +
            "-----------------------------",
            curlCommand
        );
    }
}