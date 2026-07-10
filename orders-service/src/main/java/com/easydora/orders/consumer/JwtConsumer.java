package com.easydora.orders.consumer;

import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationConstants;
import com.easydora.correlation.CorrelationContext;
import com.easydora.orders.config.JwtAuthenticationFilter;
import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.event.JwtEvent;
import com.easydora.orders.service.BuyerService;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
    public void receiveJwtCreated(
            JwtEvent event,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
            BusinessEventLog.info(logger, "jwt.created.received", event.getUserId(), "JWT created event received");

            try {
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

                // Create the userInfo object
                JwtAuthenticationFilter.JwtUserInfo userInfo =
                    new JwtAuthenticationFilter.JwtUserInfo(userId, email, firstName, lastName, role, false);

                // Add the token
                jwtAuthenticationFilter.addValidToken(token, userInfo);

                buyerService.createBuyerIfNotExists(
                    event.getUserId(),
                    event.getEmail(),
                    event.getFirstName() + " " + event.getLastName(),
                    event.getRole()
                );

            } catch (Exception e) {
                logger.error("ERROR processing JwtEvent: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to process JwtEvent", e);
            }
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }
}