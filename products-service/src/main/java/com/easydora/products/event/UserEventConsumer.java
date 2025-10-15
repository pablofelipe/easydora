package com.easydora.products.event;

import com.easydora.products.config.RabbitMQConfig;
import com.easydora.products.entity.Seller;
import com.easydora.products.entity.UserRole;
import com.easydora.products.repository.SellerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class UserEventConsumer {
    
    private static final Logger logger = LoggerFactory.getLogger(UserEventConsumer.class);
    
    private final SellerRepository sellerRepository;
    
    public UserEventConsumer(SellerRepository sellerRepository) {
        this.sellerRepository = sellerRepository;
    }
    
    @RabbitListener(queues = RabbitMQConfig.USER_REGISTERED_QUEUE)
    public void handleUserRegistered(UserEvent userEvent) {
        logger.info("Received USER_REGISTERED event for user: {}", userEvent.getUserId());
        
        try {
            Seller seller = sellerRepository.findById(userEvent.getUserId())
                .orElse(new Seller());
                
            seller.setUserId(userEvent.getUserId());
            seller.setEmail(userEvent.getEmail());
            seller.setName(userEvent.getFullName());
            
            String fullName = userEvent.getFullName();
            seller.setName(fullName);
            
            // Define role - assume BUYER como default se não especificado
            String role = userEvent.getRole() != null ? userEvent.getRole() : "BUYER";
            seller.setRole(UserRole.valueOf(role.toUpperCase()));
            seller.setActive(false); // Inativo até ativar email
            
            if (seller.getCreatedAt() == null) {
                seller.setCreatedAt(java.time.LocalDateTime.now());
            }
            seller.setUpdatedAt(java.time.LocalDateTime.now());
            
            sellerRepository.save(seller);
            logger.info("User registered: {} as {}", userEvent.getUserId(), role);
            
        } catch (Exception e) {
            logger.error("Error processing USER_REGISTERED event for user: {}", 
                userEvent.getUserId(), e);
        }
    }
    
    @RabbitListener(queues = RabbitMQConfig.JWT_CREATED_QUEUE)
    public void handleJwtCreated(UserEvent userEvent) {
        logger.info("Received JWT_CREATED event for user: {}", userEvent.getUserId());
        
        try {
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
    
    private void updateSellerFromJwtEvent(Seller seller, UserEvent userEvent) {
        // Atualiza role se fornecida
        if (userEvent.getRole() != null) {
            try {
                seller.setRole(UserRole.valueOf(userEvent.getRole().toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid role: {}, keeping current: {}", 
                    userEvent.getRole(), seller.getRole());
            }
        }
        
        // Atualiza email se necessário
        if (userEvent.getEmail() != null) {
            seller.setEmail(userEvent.getEmail());
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
        
        System.out.println("Role do evento: " + userEvent.getRole());

        if (userEvent.getRole() != null && !userEvent.getRole().isBlank()) {
            try {
                seller.setRole(UserRole.valueOf(userEvent.getRole().toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid role: {}, defaulting to BUYER", userEvent.getRole());
                seller.setRole(UserRole.BUYER);
            }
        } else if (seller.getRole() == null) {
            seller.setRole(UserRole.BUYER); // default
        }
        
        seller.setActive(true);
        seller.setCreatedAt(java.time.LocalDateTime.now());
        seller.setUpdatedAt(java.time.LocalDateTime.now());
        
        sellerRepository.save(seller);
    }
}