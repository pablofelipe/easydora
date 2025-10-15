package com.easydora.products.consumer;

import com.easydora.products.config.JwtAuthenticationFilter;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class JwtConsumer {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public JwtConsumer(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = new ObjectMapper();
    }

    @RabbitListener(queues = "products.jwt.created.queue")
    public void receiveJwtCreated(org.springframework.amqp.core.Message message) {
        try {
            String jsonString = new String(message.getBody());
            System.out.println("📨 Mensagem RAW recebida: " + jsonString);
            
            JsonNode rootNode = objectMapper.readTree(jsonString);
            
            String token = rootNode.get("token").asText();
            
            JwtAuthenticationFilter.JwtUserInfo user = new JwtAuthenticationFilter.JwtUserInfo(
                rootNode.get("userId").asLong(),
                rootNode.get("email").asText(),
                rootNode.get("firstName").asText(),
                rootNode.get("lastName").asText(),
                rootNode.get("roles").asText() 
            );
            
            jwtAuthenticationFilter.addValidToken(token, user);
            System.out.println("✅ Token JWT recebido e armazenado para usuário: " + user.getEmail());
            
        } catch (Exception e) {
            System.err.println("❌ Erro ao processar mensagem JWT: " + e.getMessage());
            e.printStackTrace();
        }
    }
}