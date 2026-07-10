package com.easydora.billing.consumer;

import com.easydora.billing.config.JwtAuthenticationFilter;
import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.billing.event.JwtEvent;
import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationConstants;
import com.easydora.correlation.CorrelationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
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
    public void receiveJwtCreated(
            JwtEvent event,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
            String token = event.getToken();
            if (token == null || token.isBlank()) {
                logger.warn("Received jwt.created event with no token, ignoring: {}", event);
                return;
            }

            JwtAuthenticationFilter.JwtUserInfo userInfo = new JwtAuthenticationFilter.JwtUserInfo(
                    event.getUserId(), event.getEmail(), event.getFirstName(), event.getLastName(), event.getRole());
            jwtAuthenticationFilter.addValidToken(token, userInfo);
            BusinessEventLog.info(logger, "jwt.created.received", event.getUserId(), "Cached broadcast token for " + event.getEmail());
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }
}
