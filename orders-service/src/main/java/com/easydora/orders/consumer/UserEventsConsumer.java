package com.easydora.orders.consumer;

import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.config.JwtAuthenticationFilter;
import com.easydora.orders.entity.Buyer;
import com.easydora.orders.entity.UserRole;
import com.easydora.orders.event.UserEvent;
import com.easydora.orders.repository.BuyerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

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
    public void handleUserRegistered(UserEvent userEvent) {
        logger.info("Received USER_REGISTERED event for user: {}", userEvent.getUserId());
        
        try {
            // Para orders-service, estamos interessados principalmente em BUYERS
            // Mas também podemos ter SELLERS fazendo compras
            if (!userEvent.isBuyer() && !userEvent.isSeller()) {
                logger.info("User {} is not a BUYER or SELLER, skipping", userEvent.getUserId());
                return;
            }
            
            Buyer buyer = buyerRepository.findById(userEvent.getUserId())
                .orElse(new Buyer());
                
            buyer.setUserId(userEvent.getUserId());
            buyer.setEmail(userEvent.getEmail());
            buyer.setName(userEvent.getFullName());
            
            String role = userEvent.getRole();
            if (role == null || role.trim().isEmpty()) {
                logger.warn("Role não encontrado no evento USER_REGISTERED para user: {}", userEvent.getUserId());
                role = "BUYER"; // Default para orders-service
            }
            
            try {
                buyer.setRole(UserRole.valueOf(role.toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid role: {}, defaulting to BUYER", role);
                buyer.setRole(UserRole.BUYER);
            }
            
            buyer.setActive(false); // Inativo até ativar email
            
            if (buyer.getCreatedAt() == null) {
                buyer.setCreatedAt(java.time.LocalDateTime.now());
            }
            buyer.setUpdatedAt(java.time.LocalDateTime.now());
            
            buyerRepository.save(buyer);
            logger.info("Buyer registered: {} as {}", userEvent.getUserId(), buyer.getRole());
            
        } catch (Exception e) {
            logger.error("Error processing USER_REGISTERED event for user: {}", 
                userEvent.getUserId(), e);
        }
    }
    
    @RabbitListener(queues = RabbitMQConfig.JWT_CREATED_PROFILE_QUEUE)
    public void handleJwtCreated(UserEvent userEvent) {
        logger.info("Received JWT_CREATED event for user: {}", userEvent.getUserId());
        
        try {
            // Para orders-service, estamos interessados principalmente em BUYERS
            if (!userEvent.isBuyer() && !userEvent.isSeller()) {
                logger.info("User {} is not a BUYER or SELLER, skipping", userEvent.getUserId());
                return;
            }
            boolean isUserActive = buyerRepository.findById(userEvent.getUserId())
                .map(Buyer::isActive)
                .orElse(false); // Se não encontrou, assume false
            
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
            
            // Armazenar o token JWT para autenticação
            if (userEvent.getToken() != null) {
                JwtAuthenticationFilter.JwtUserInfo userInfo = new JwtAuthenticationFilter.JwtUserInfo(
                    userEvent.getUserId(),
                    userEvent.getEmail(),
                    userEvent.getFirstName(),
                    userEvent.getLastName(),
                    userEvent.getRole(),
                    isUserActive
                );
                jwtAuthenticationFilter.addValidToken(userEvent.getToken(), userInfo);
                logger.info("JWT token stored for user: {}", userEvent.getUserId());
            }
            
        } catch (Exception e) {
            logger.error("Error processing JWT_CREATED event for user: {}", 
                userEvent.getUserId(), e);
        }
    }
    
    @RabbitListener(queues = RabbitMQConfig.USER_VERIFIED_QUEUE)
    public void handleUserVerified(Long userId) {
        logger.info("Received USER_VERIFIED_QUEUE event for user: {}", userId);

        try {
            Buyer buyer = buyerRepository.findById(userId)
                .orElseThrow(() -> new Exception("Buyer not found: " + userId));
                
            buyer.setActive(true); // Ativa o usuário após verificação
            buyer.setUpdatedAt(java.time.LocalDateTime.now());
            
            buyerRepository.save(buyer);
            logger.info("Buyer activated: {}", userId);
            
        } catch (Exception e) {
            logger.error("Error processing USER_VERIFIED_QUEUE event for user: {}", 
                userId, e);
        }
    }

    private void updateBuyerFromJwtEvent(Buyer buyer, UserEvent userEvent) {
        // Atualiza role se fornecida (pode ser BUYER ou SELLER)
        if (userEvent.getRole() != null) {
            try {
                buyer.setRole(UserRole.valueOf(userEvent.getRole().toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid role: {}, keeping current: {}", 
                    userEvent.getRole(), buyer.getRole());
            }
        }
        
        // Atualiza email se necessário
        if (userEvent.getEmail() != null) {
            buyer.setEmail(userEvent.getEmail());
        }
        
        // Atualiza nome se necessário
        if (userEvent.hasNameInfo()) {
            buyer.setName(userEvent.getFullName());
        }
        
        // Ativa o usuário quando faz login
        buyer.setActive(true);
        buyer.setUpdatedAt(java.time.LocalDateTime.now());
    }
    
    private void createBuyerFromJwtEvent(UserEvent userEvent) {
        Buyer buyer = new Buyer();
        buyer.setUserId(userEvent.getUserId());
        buyer.setEmail(userEvent.getEmail());
        buyer.setName(userEvent.getFullName());
        
        logger.info("Role do evento: {}", userEvent.getRole());

        if (userEvent.getRole() != null && !userEvent.getRole().isBlank()) {
            try {
                buyer.setRole(UserRole.valueOf(userEvent.getRole().toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid role: {}, defaulting to BUYER", userEvent.getRole());
                buyer.setRole(UserRole.BUYER);
            }
        } else {
            buyer.setRole(UserRole.BUYER); // default para orders-service
        }
        
        buyer.setActive(true); // Ativo quando faz login
        buyer.setCreatedAt(java.time.LocalDateTime.now());
        buyer.setUpdatedAt(java.time.LocalDateTime.now());
        
        buyerRepository.save(buyer);
    }
}