package com.easydora.products.consumer;

import com.easydora.products.config.RabbitMQConfig;
import com.easydora.products.entity.Seller;
import com.easydora.products.entity.UserRole;
import com.easydora.products.event.UserEvent;
import com.easydora.products.exception.UnauthorizedException;
import com.easydora.products.repository.SellerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;


@Service
public class UserEventConsumer {
    
    private static final Logger logger = LoggerFactory.getLogger(UserEventConsumer.class);
    
    private final SellerRepository sellerRepository;
    
    private static final String SELLER_ROLE = "SELLER";

    public UserEventConsumer(SellerRepository sellerRepository) {
        this.sellerRepository = sellerRepository;
    }
    
    @RabbitListener(queues = RabbitMQConfig.USER_REGISTERED_QUEUE)
    public void handleUserRegistered(UserEvent userEvent) {
        logger.info("Received USER_REGISTERED event for user: {}", userEvent.getUserId());
        
        try {

            if (!isSeller(userEvent)) {
                logger.debug("Ignoring non-SELLER registration: {} as {}", 
                    userEvent.getUserId(), userEvent.getRole());
                return;
            }
            
            Seller seller = sellerRepository.findById(userEvent.getUserId())
                .orElse(new Seller());
                
            seller.setUserId(userEvent.getUserId());
            seller.setEmail(userEvent.getEmail());
            seller.setName(userEvent.getFullName());
            seller.setRole(UserRole.SELLER);
            seller.setName(userEvent.getFullName());
            seller.setActive(false); // Inativo até ativar email
            
            if (seller.getCreatedAt() == null) {
                seller.setCreatedAt(java.time.LocalDateTime.now());
            }
            seller.setUpdatedAt(java.time.LocalDateTime.now());
            
            sellerRepository.save(seller);
            
        } catch (Exception e) {
            logger.error("Error processing USER_REGISTERED event for user: {}", 
                userEvent.getUserId(), e);
        }
    }

    private boolean isSeller(UserEvent userEvent) {
        return userEvent.getRole() != null && 
               SELLER_ROLE.equalsIgnoreCase(userEvent.getRole().trim());
    }

    @RabbitListener(queues = RabbitMQConfig.JWT_CREATED_QUEUE)
    public void handleJwtCreated(UserEvent userEvent) {
        logger.info("Received JWT_CREATED event for user: {}", userEvent.getUserId());
        
        try {
            if (!isSeller(userEvent)) {
                logger.debug("Ignoring non-SELLER JWT event: {} as {}", 
                    userEvent.getUserId(), userEvent.getRole());
                return;
            }

            sellerRepository.findById(userEvent.getUserId()).ifPresentOrElse(
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
        }
    }
    
    @RabbitListener(queues = RabbitMQConfig.USER_VERIFIED_QUEUE)
    public void handleUserVerified(Long userId) {
        logger.info("Received USER_VERIFIED_QUEUE event for user: {}", userId);

        try 
        {
            Seller seller = sellerRepository.findById(userId.toString())
                .orElseThrow(() -> new Exception("Seller not found: " + userId));
                
            seller.setActive(true); // Ativa o usuário após verificação

            sellerRepository.save(seller);
        } catch (Exception e) {
            logger.error("Error processing USER_VERIFIED_QUEUE event for user: {}", 
                userId, e);
        }
    }

    private void updateSellerFromJwtEvent(Seller seller, UserEvent userEvent) {
        // Atualiza role se fornecida
        seller.setRole(UserRole.SELLER);
        
        // Atualiza email se necessário
        if (userEvent.getEmail() != null) {
            seller.setEmail(userEvent.getEmail());
        }
        
        if (userEvent.getFullName() != null) {
            seller.setName(userEvent.getFullName());
        }

        // Ativa o usuário quando faz login
        seller.setActive(true);
        seller.setUpdatedAt(java.time.LocalDateTime.now());
    }
    
    private void createSellerFromJwtEvent(UserEvent userEvent) {
        Seller seller = new Seller();
        seller.setUserId(userEvent.getUserId());
        seller.setEmail(userEvent.getEmail());
        seller.setName(userEvent.getFullName());
        seller.setRole(UserRole.SELLER);
        seller.setActive(true);
        seller.setCreatedAt(java.time.LocalDateTime.now());
        seller.setUpdatedAt(java.time.LocalDateTime.now());
        
        sellerRepository.save(seller);
    }
}