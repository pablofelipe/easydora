package com.easydora.products.consumer;

import com.easydora.products.config.JwtAuthenticationFilter;
import com.easydora.products.config.RabbitMQConfig;
import com.easydora.products.event.JwtEvent;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class JwtConsumer {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(JwtConsumer.class);

    public JwtConsumer(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = new ObjectMapper();
    }

    @RabbitListener(queues = RabbitMQConfig.JWT_CREATED_QUEUE)
    public void receiveJwtCreated(JwtEvent event) {
        try {
            logger.info("📨 📨 📨 JWT EVENT RECEBIDO 📨 📨 📨");
            logger.info("📨 Evento: {}", event.toString());
            
            String token = event.getToken();
            Long userId = event.getUserId();
            String email = event.getEmail();
            String firstName = event.getFirstName();
            String lastName = event.getLastName();
            String role = event.getRole();
            
            if (token == null || token.trim().isEmpty()) {
                logger.error("Token não encontrado no evento");
                return;
            }
            
            logger.info("Token extraído (primeiros 20 chars): {}...", 
                token.substring(0, Math.min(20, token.length())));
            logger.info("Dados do usuário: userId={}, email={}, role={}", userId, email, role);
            
            // Cria o objeto userInfo
            JwtAuthenticationFilter.JwtUserInfo userInfo = 
                new JwtAuthenticationFilter.JwtUserInfo(userId, email, firstName, lastName, role);
            
            // Adiciona o token
            jwtAuthenticationFilter.addValidToken(token, userInfo);

            logger.info("TOKEN ARMAZENADO COM SUCESSO!");
            logger.info("Usuário: {}", email);
            logger.info("Role: {}", role);
            logger.info("Total de tokens armazenados: {}", 
                jwtAuthenticationFilter.getClass()
                    .getDeclaredMethod("getValidTokensSize")
                    .invoke(jwtAuthenticationFilter));
            
        } catch (Exception e) {
            logger.error("ERRO ao processar JwtEvent: {}", e.getMessage(), e);
        }
    }
}