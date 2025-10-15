package com.easydora.products.controller;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import com.easydora.products.config.JwtAuthenticationFilter;

@RestController
@RequestMapping("/debug")
public class DebugController {
    
    private final JwtAuthenticationFilter jwtFilter;
    
    public DebugController(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }
    
    @GetMapping("/tokens")
    public String listTokens() {
        jwtFilter.listTokens();
        return "Check logs for token list";
    }
    
    @GetMapping("/health")
    public String health() {
        return "Products Service is UP";
    }
}
