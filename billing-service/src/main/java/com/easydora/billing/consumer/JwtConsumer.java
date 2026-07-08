package com.easydora.billing.consumer;

import com.easydora.billing.config.JwtAuthenticationFilter;
import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.billing.event.JwtEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes auth-service's broadcast JwtCreatedEvent and caches the token in
 * JwtAuthenticationFilter -- the same pattern orders-service/products-service
 * use. billing-service has no Buyer/Seller-style local entity to create as a
 * side effect, unlike orders-service's version.
 */
@Component
public class JwtConsumer {

    private static final Logger logger = LoggerFactory.getLogger(JwtConsumer.class);

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public JwtConsumer(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @RabbitListener(queues = RabbitMQConfig.JWT_CREATED_QUEUE)
    public void receiveJwtCreated(JwtEvent event) {
        String token = event.getToken();
        if (token == null || token.isBlank()) {
            logger.warn("Received jwt.created event with no token, ignoring: {}", event);
            return;
        }

        JwtAuthenticationFilter.JwtUserInfo userInfo = new JwtAuthenticationFilter.JwtUserInfo(
                event.getUserId(), event.getEmail(), event.getFirstName(), event.getLastName(), event.getRole());
        jwtAuthenticationFilter.addValidToken(token, userInfo);
        logger.info("Cached broadcast token for user: {}", event.getEmail());
    }
}
