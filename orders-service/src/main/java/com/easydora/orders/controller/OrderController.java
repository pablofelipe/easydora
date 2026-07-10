package com.easydora.orders.controller;

import com.easydora.orders.config.JwtAuthenticationFilter.JwtUserInfo;
import com.easydora.orders.dto.OrderRequest;
import com.easydora.orders.dto.OrderResponse;
import com.easydora.orders.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class OrderController {
    
    private final OrderService orderService;
    
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "OK");
        response.put("service", "orders-service");
        response.put("port", "8084");
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        Map<String, String> response = new HashMap<>();
        response.put("message", "pong from orders service");
        response.put("port", "8084");
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/createOrder")
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody OrderRequest request,
            @AuthenticationPrincipal JwtUserInfo principal) {
        OrderResponse response = orderService.createOrder(request, principal.getUserId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable String orderId,
            @AuthenticationPrincipal JwtUserInfo principal) {
        OrderResponse response = orderService.getOrder(orderId, principal.getUserId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/user")
    public ResponseEntity<List<OrderResponse>> getUserOrders(
            @AuthenticationPrincipal JwtUserInfo principal) {
        List<OrderResponse> orders = orderService.getUserOrders(principal.getUserId());
        return ResponseEntity.ok(orders);
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable String orderId,
            @AuthenticationPrincipal JwtUserInfo principal) {
        OrderResponse response = orderService.cancelOrder(orderId, principal.getUserId());
        return ResponseEntity.ok(response);
    }
}