package com.easydora.orders.consumer;

import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationConstants;
import com.easydora.correlation.CorrelationContext;
import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.config.JwtAuthenticationFilter;
import com.easydora.orders.entity.Buyer;
import com.easydora.orders.entity.UserRole;
import com.easydora.orders.event.UserEvent;
import com.easydora.orders.repository.BuyerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserEventsConsumer {
    
    private static final Logger logger = LoggerFactory.getLogger(UserEventsConsumer.class);
    
    private final BuyerRepository buyerRepository;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    
    public UserEventsConsumer(BuyerRepository buyerRepository, 
                             JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.buyerRepository = buyerRepository;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }
    
    @RabbitListener(queues = RabbitMQConfig.USER_REGISTERED_QUEUE)
    public void handleUserRegistered(
            UserEvent userEvent,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
        BusinessEventLog.info(logger, "user.registered.received", userEvent.getUserId(), "Received USER_REGISTERED event");

        try {
            // For orders-service, we're mainly interested in BUYERS
            // But we may also have SELLERS making purchases
            if (!userEvent.isBuyer() && !userEvent.isSeller()) {
                logger.info("User {} is not a BUYER or SELLER, skipping", userEvent.getUserId());
                return;
            }
            
            Optional<Buyer> existingBuyer = buyerRepository.findById(userEvent.getUserId());
            Buyer buyer = existingBuyer.orElse(new Buyer());

            buyer.setUserId(userEvent.getUserId());
            buyer.setEmail(userEvent.getEmail());
            buyer.setName(userEvent.getFullName());

            String role = userEvent.getRole();
            if (role == null || role.trim().isEmpty()) {
                logger.warn("Role not found in USER_REGISTERED event for user: {}", userEvent.getUserId());
                role = "BUYER"; // Default for orders-service
            }

            try {
                buyer.setRole(UserRole.valueOf(role.toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid role: {}, defaulting to BUYER", role);
                buyer.setRole(UserRole.BUYER);
            }

            // Only a genuinely new buyer starts inactive. auth-service's
            // registerUser now publishes user.registered through the
            // Outbox (up to a 5s poll delay), so this event is no longer
            // guaranteed to arrive before jwt.created/user.verified for
            // the same user -- unconditionally forcing active=false here
            // would silently deactivate a buyer a later-received event
            // already activated correctly.
            if (existingBuyer.isEmpty()) {
                buyer.setActive(false); // Inactive until the email is activated
            }

            if (buyer.getCreatedAt() == null) {
                buyer.setCreatedAt(java.time.LocalDateTime.now());
            }
            buyer.setUpdatedAt(java.time.LocalDateTime.now());
            
            buyerRepository.save(buyer);
            logger.info("Buyer registered: {} as {}", userEvent.getUserId(), buyer.getRole());
            
        } catch (Exception e) {
            logger.error("Error processing USER_REGISTERED event for user: {}",
                userEvent.getUserId(), e);
            throw new RuntimeException("Failed to process USER_REGISTERED event for user " + userEvent.getUserId(), e);
        }
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.JWT_CREATED_PROFILE_QUEUE)
    public void handleJwtCreated(
            UserEvent userEvent,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
        BusinessEventLog.info(logger, "jwt.created.received", userEvent.getUserId(), "Received JWT_CREATED event");
        
        try {
            // For orders-service, we're mainly interested in BUYERS
            if (!userEvent.isBuyer() && !userEvent.isSeller()) {
                logger.info("User {} is not a BUYER or SELLER, skipping", userEvent.getUserId());
                return;
            }
            boolean isUserActive = buyerRepository.findById(userEvent.getUserId())
                .map(Buyer::isActive)
                .orElse(false); // If not found, assume false
            
            buyerRepository.findById(userEvent.getUserId()).ifPresentOrElse(
                buyer -> {
                    updateBuyerFromJwtEvent(buyer, userEvent);
                    buyerRepository.save(buyer);
                    logger.info("Buyer updated: {} as {}", userEvent.getUserId(), buyer.getRole());
                },
                () -> {
                    createBuyerFromJwtEvent(userEvent);
                    logger.info("New buyer created: {} as {}", userEvent.getUserId(), userEvent.getRole());
                }
            );
            
            // Store the JWT token for authentication. ADR-0039: give the
            // cache entry a lifetime equal to the JWT's own expiresIn when
            // the broadcast carries it, instead of caching it forever
            // until a restart.
            if (userEvent.getToken() != null) {
                JwtAuthenticationFilter.JwtUserInfo userInfo;
                if (userEvent.getCreatedAt() != null && userEvent.getExpiresIn() != null) {
                    userInfo = new JwtAuthenticationFilter.JwtUserInfo(
                        userEvent.getUserId(),
                        userEvent.getEmail(),
                        userEvent.getFirstName(),
                        userEvent.getLastName(),
                        userEvent.getRole(),
                        isUserActive,
                        userEvent.getCreatedAt().plusSeconds(userEvent.getExpiresIn())
                    );
                } else {
                    userInfo = new JwtAuthenticationFilter.JwtUserInfo(
                        userEvent.getUserId(),
                        userEvent.getEmail(),
                        userEvent.getFirstName(),
                        userEvent.getLastName(),
                        userEvent.getRole(),
                        isUserActive
                    );
                }
                jwtAuthenticationFilter.addValidToken(userEvent.getToken(), userInfo);
                logger.info("JWT token stored for user: {}", userEvent.getUserId());
            }
            
        } catch (Exception e) {
            logger.error("Error processing JWT_CREATED event for user: {}",
                userEvent.getUserId(), e);
            throw new RuntimeException("Failed to process JWT_CREATED event for user " + userEvent.getUserId(), e);
        }
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.USER_VERIFIED_QUEUE)
    public void handleUserVerified(
            Long userId,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
        BusinessEventLog.info(logger, "user.verified.received", userId, "Received USER_VERIFIED_QUEUE event");

        try {
            Buyer buyer = buyerRepository.findById(userId)
                .orElseThrow(() -> new Exception("Buyer not found: " + userId));

            buyer.setActive(true); // Activate the user after verification
            buyer.setUpdatedAt(java.time.LocalDateTime.now());

            buyerRepository.save(buyer);
            BusinessEventLog.info(logger, "buyer.activated", userId, "Buyer activated");

        } catch (Exception e) {
            logger.error("Error processing USER_VERIFIED_QUEUE event for user: {}",
                userId, e);
            throw new RuntimeException("Failed to process USER_VERIFIED_QUEUE event for user " + userId, e);
        }
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }

    private void updateBuyerFromJwtEvent(Buyer buyer, UserEvent userEvent) {
        // Update role if provided (can be BUYER or SELLER)
        if (userEvent.getRole() != null) {
            try {
                buyer.setRole(UserRole.valueOf(userEvent.getRole().toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid role: {}, keeping current: {}", 
                    userEvent.getRole(), buyer.getRole());
            }
        }
        
        // Update email if needed
        if (userEvent.getEmail() != null) {
            buyer.setEmail(userEvent.getEmail());
        }

        // Update name if needed
        if (userEvent.hasNameInfo()) {
            buyer.setName(userEvent.getFullName());
        }

        // Activate the user when they log in
        buyer.setActive(true);
        buyer.setUpdatedAt(java.time.LocalDateTime.now());
    }
    
    private void createBuyerFromJwtEvent(UserEvent userEvent) {
        Buyer buyer = new Buyer();
        buyer.setUserId(userEvent.getUserId());
        buyer.setEmail(userEvent.getEmail());
        buyer.setName(userEvent.getFullName());
        
        logger.info("Role from the event: {}", userEvent.getRole());

        if (userEvent.getRole() != null && !userEvent.getRole().isBlank()) {
            try {
                buyer.setRole(UserRole.valueOf(userEvent.getRole().toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid role: {}, defaulting to BUYER", userEvent.getRole());
                buyer.setRole(UserRole.BUYER);
            }
        } else {
            buyer.setRole(UserRole.BUYER); // default for orders-service
        }

        buyer.setActive(true); // Active when they log in
        buyer.setCreatedAt(java.time.LocalDateTime.now());
        buyer.setUpdatedAt(java.time.LocalDateTime.now());
        
        buyerRepository.save(buyer);
    }
}