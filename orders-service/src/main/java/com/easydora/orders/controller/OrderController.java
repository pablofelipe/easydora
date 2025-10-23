package com.easydora.orders.controller;

import com.easydora.orders.dto.OrderRequest;
import com.easydora.orders.dto.OrderResponse;
import com.easydora.orders.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {
    
    private final OrderService orderService;
    
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }
    
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody OrderRequest request,
            @RequestHeader("X-User-Id") Long userId) {
        OrderResponse response = orderService.createOrder(request, userId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable String orderId,
            @RequestHeader("X-User-Id") Long userId) {
        OrderResponse response = orderService.getOrder(orderId, userId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/user")
    public ResponseEntity<List<OrderResponse>> getUserOrders(
            @RequestHeader("X-User-Id") Long userId) {
        List<OrderResponse> orders = orderService.getUserOrders(userId);
        return ResponseEntity.ok(orders);
    }
    
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable String orderId,
            @RequestHeader("X-User-Id") Long userId) {
        OrderResponse response = orderService.cancelOrder(orderId, userId);
        return ResponseEntity.ok(response);
    }
}