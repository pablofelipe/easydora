package com.easydora.orders.consumer;

import com.easydora.orders.config.JwtAuthenticationFilter;
import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.event.JwtEvent;
import com.easydora.orders.service.BuyerService;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class JwtConsumer {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private BuyerService buyerService;
    private static final Logger logger = LoggerFactory.getLogger(JwtConsumer.class);

    public JwtConsumer(JwtAuthenticationFilter jwtAuthenticationFilter, BuyerService buyerService) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.buyerService = buyerService;
    }

    @RabbitListener(queues = RabbitMQConfig.JWT_CREATED_QUEUE)
    public void receiveJwtCreated(JwtEvent event) {
        try {
            logger.info("--- JWT EVENT RECEIVED ---");
            logger.info("Event: {}", event.toString());

            String token = event.getToken();
            Long userId = event.getUserId();
            String email = event.getEmail();
            String firstName = event.getFirstName();
            String lastName = event.getLastName();
            String role = event.getRole();

            if (token == null || token.trim().isEmpty()) {
                logger.error("Token not found in event");
                return;
            }

            logger.info("Token extracted (first 20 chars): {}...",
                token.substring(0, Math.min(20, token.length())));
            logger.info("User data: userId={}, email={}, role={}", userId, email, role);

            // Create the userInfo object
            JwtAuthenticationFilter.JwtUserInfo userInfo =
                new JwtAuthenticationFilter.JwtUserInfo(userId, email, firstName, lastName, role, false);

            // Add the token
            jwtAuthenticationFilter.addValidToken(token, userInfo);

            logger.info("TOKEN STORED SUCCESSFULLY!");
            logger.info("User: {}", email);
            logger.info("Role: {}", role);
            logger.info("Total stored tokens: {}",
                jwtAuthenticationFilter.getClass()
                    .getDeclaredMethod("getValidTokensSize")
                    .invoke(jwtAuthenticationFilter));
            
            buyerService.createBuyerIfNotExists(
                event.getUserId(),
                event.getEmail(),
                event.getFirstName() + " " + event.getLastName(),
                event.getRole()
            );

        } catch (Exception e) {
            logger.error("ERROR processing JwtEvent: {}", e.getMessage(), e);
        }
    }
}