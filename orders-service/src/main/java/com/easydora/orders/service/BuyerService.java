package com.easydora.orders.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.easydora.orders.entity.Buyer;
import com.easydora.orders.entity.UserRole;
import com.easydora.orders.repository.BuyerRepository;

import java.time.LocalDateTime;

@Service
public class BuyerService {
    @Autowired
    private final BuyerRepository buyerRepository;
    private static final Logger logger = LoggerFactory.getLogger(BuyerService.class);

    public BuyerService(BuyerRepository buyerRepository) {
        this.buyerRepository = buyerRepository;
    }

    @Transactional
    public void createBuyerIfNotExists(Long userId, String email, String name, String role) {
        if (!buyerRepository.existsById(userId)) {
            Buyer buyer = new Buyer();
            buyer.setUserId(userId);
            buyer.setEmail(email);
            buyer.setName(name);
            
            try {
                buyer.setRole(UserRole.valueOf(role.toUpperCase()));
            } catch (IllegalArgumentException e) {
                buyer.setRole(UserRole.BUYER);
            }
            
            buyer.setActive(true);
            buyer.setCreatedAt(LocalDateTime.now());
            
            buyerRepository.save(buyer);
            logger.info("Buyer created: {}", email);
        }
    }
}