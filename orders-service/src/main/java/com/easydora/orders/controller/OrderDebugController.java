package com.easydora.orders.controller;

import com.easydora.orders.config.JwtAuthenticationFilter;
import com.easydora.orders.repository.BuyerRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/debug")
public class OrderDebugController {
    
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final BuyerRepository buyerRepository;
    
    public OrderDebugController(JwtAuthenticationFilter jwtAuthenticationFilter,
                               BuyerRepository buyerRepository) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.buyerRepository = buyerRepository;
    }
    
    @GetMapping("/debug/tokens")
    public ResponseEntity<Map<String, Object>> listTokens() {
        jwtAuthenticationFilter.listTokens();
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Check logs for token list");
        response.put("tokenCount", jwtAuthenticationFilter.getValidTokensSize());
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/debug/buyers")
    public ResponseEntity<Map<String, Object>> listBuyers() {
        long buyerCount = buyerRepository.count();
        
        Map<String, Object> response = new HashMap<>();
        response.put("buyerCount", buyerCount);
        response.put("buyers", buyerRepository.findAll());
        
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/debug/clear-tokens")
    public ResponseEntity<Map<String, String>> clearTokens() {

        Map<String, String> response = new HashMap<>();
        response.put("message", "Token clearance would be implemented here");
        return ResponseEntity.ok(response);
    }
}