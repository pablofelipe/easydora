package com.easydora.products.consumer;

import com.easydora.products.config.JwtAuthenticationFilter;
import com.easydora.products.config.RabbitMQConfig;
import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationConstants;
import com.easydora.correlation.CorrelationContext;
import com.easydora.products.entity.Seller;
import com.easydora.products.entity.UserRole;
import com.easydora.products.event.UserEvent;
import com.easydora.products.repository.SellerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;


@Service
public class UserEventConsumer {
    
    private static final Logger logger = LoggerFactory.getLogger(UserEventConsumer.class);
    
    private final SellerRepository sellerRepository;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private static final String SELLER_ROLE = "SELLER";

    public UserEventConsumer(SellerRepository sellerRepository, JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.sellerRepository = sellerRepository;
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

                if (!isSeller(userEvent)) {
                    logger.debug("Ignoring non-SELLER registration: {} as {}",
                        userEvent.getUserId(), userEvent.getRole());
                    return;
                }

                Seller seller = sellerRepository.findById(userEvent.getUserId().toString())
                    .orElse(new Seller());

                seller.setUserId(userEvent.getUserId().toString());
                seller.setEmail(userEvent.getEmail());
                seller.setName(userEvent.getFullName());
                seller.setRole(UserRole.SELLER);
                seller.setName(userEvent.getFullName());
                seller.setActive(false); // Inactive until email is activated

                if (seller.getCreatedAt() == null) {
                    seller.setCreatedAt(java.time.LocalDateTime.now());
                }
                seller.setUpdatedAt(java.time.LocalDateTime.now());

                sellerRepository.save(seller);

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

    private boolean isSeller(UserEvent userEvent) {
        return userEvent.getRole() != null && 
               SELLER_ROLE.equalsIgnoreCase(userEvent.getRole().trim());
    }

    @RabbitListener(queues = RabbitMQConfig.JWT_CREATED_QUEUE)
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
                if (!isSeller(userEvent)) {
                    logger.debug("Ignoring non-SELLER JWT event: {} as {}",
                        userEvent.getUserId(), userEvent.getRole());
                    return;
                }

                addValidToken(userEvent);

                sellerRepository.findById(userEvent.getUserId().toString()).ifPresentOrElse(
                    seller -> {
                        updateSellerFromJwtEvent(seller, userEvent);
                        sellerRepository.save(seller);
                        logger.info("User updated: {} as {}", userEvent.getUserId(), seller.getRole());
                    },
                    () -> {
                        createSellerFromJwtEvent(userEvent);
                        logger.info("New user created: {} as {}", userEvent.getUserId(), userEvent.getRole());
                    }
                );

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
    
    private void addValidToken(UserEvent event) {
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

        // Create the userInfo object. ADR-0039: give the cache entry a
        // lifetime equal to the JWT's own expiresIn when the broadcast
        // carries it, instead of caching it forever until a restart.
        JwtAuthenticationFilter.JwtUserInfo userInfo;
        if (event.getCreatedAt() != null && event.getExpiresIn() != null) {
            userInfo = new JwtAuthenticationFilter.JwtUserInfo(
                userId, email, firstName, lastName, role, event.getCreatedAt().plusSeconds(event.getExpiresIn()));
        } else {
            userInfo = new JwtAuthenticationFilter.JwtUserInfo(userId, email, firstName, lastName, role);
        }

        // Add the token
        jwtAuthenticationFilter.addValidToken(token, userInfo);
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
                // auth-service publishes user.verified for every user regardless
                // of role (the event is just a bare userId, no role field to
                // filter on here the way user.registered/jwt.created can via
                // isSeller()) -- a BUYER's verification reaches this queue too,
                // so no Seller row existing yet is expected, not a failure.
                sellerRepository.findById(userId.toString()).ifPresentOrElse(
                    seller -> {
                        seller.setActive(true); // Activate the seller after verification
                        sellerRepository.save(seller);
                        BusinessEventLog.info(logger, "seller.activated", userId, "Seller activated");
                    },
                    () -> logger.debug("Ignoring USER_VERIFIED_QUEUE event for non-seller user: {}", userId)
                );
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

    private void updateSellerFromJwtEvent(Seller seller, UserEvent userEvent) {
        // Update role if provided
        seller.setRole(UserRole.SELLER);

        // Update email if needed
        if (userEvent.getEmail() != null) {
            seller.setEmail(userEvent.getEmail());
        }

        if (userEvent.getFullName() != null) {
            seller.setName(userEvent.getFullName());
        }

        // Activate the user when they log in
        seller.setActive(true);
        seller.setUpdatedAt(java.time.LocalDateTime.now());
    }
    
    private void createSellerFromJwtEvent(UserEvent userEvent) {
        Seller seller = new Seller();
        seller.setUserId(userEvent.getUserId().toString());
        seller.setEmail(userEvent.getEmail());
        seller.setName(userEvent.getFullName());
        seller.setRole(UserRole.SELLER);
        seller.setActive(true);
        seller.setCreatedAt(java.time.LocalDateTime.now());
        seller.setUpdatedAt(java.time.LocalDateTime.now());
        
        sellerRepository.save(seller);
    }
}