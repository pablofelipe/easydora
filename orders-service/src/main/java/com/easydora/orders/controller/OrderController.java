package com.easydora.orders.controller;

import com.easydora.orders.config.JwtAuthenticationFilter.JwtUserInfo;
import com.easydora.orders.dto.OrderRequest;
import com.easydora.orders.dto.OrderResponse;
import com.easydora.orders.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class OrderController {

    private final OrderService orderService;
    private final DataSource dataSource;

    public OrderController(OrderService orderService, DataSource dataSource) {
        this.orderService = orderService;
        this.dataSource = dataSource;
    }

    // A short-timeout, real connectivity probe (see ADR-0010's residual
    // gap: this endpoint -- the one Docker's own HEALTHCHECK and the
    // Gateway route hit -- used to report a hardcoded "OK" with no real
    // dependency probe at all). 2s is generous against this project's own
    // measured healthy-backend latencies (100-115ms, ADR-0006) while still
    // bounding how long a caller waits on a genuinely stuck connection.
    private boolean isDatabaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean databaseReachable = isDatabaseReachable();

        Map<String, String> response = new HashMap<>();
        response.put("status", databaseReachable ? "OK" : "DOWN");
        response.put("service", "orders-service");
        response.put("port", "8084");
        response.put("database", databaseReachable ? "Connected" : "Disconnected");

        return databaseReachable
            ? ResponseEntity.ok(response)
            : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
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

    // Platform-operations action: any order, gated by role (not
    // ownership) since no single seller owns a whole order. This is the
    // first role-gated (as opposed to ownership-gated) mutation in this
    // service -- see OrderService.shipOrder for why.
    @PostMapping("/{orderId}/ship")
    public ResponseEntity<OrderResponse> shipOrder(
            @PathVariable String orderId,
            @AuthenticationPrincipal JwtUserInfo principal) {
        if (!"ADMIN".equals(principal.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        OrderResponse response = orderService.shipOrder(orderId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{orderId}/deliver")
    public ResponseEntity<OrderResponse> deliverOrder(
            @PathVariable String orderId,
            @AuthenticationPrincipal JwtUserInfo principal) {
        OrderResponse response = orderService.deliverOrder(orderId, principal.getUserId());
        return ResponseEntity.ok(response);
    }

    // Platform-operations read model: orders currently waiting to be
    // shipped. Same role gate as shipOrder.
    @GetMapping("/fulfillment")
    public ResponseEntity<List<OrderResponse>> getFulfillmentQueue(
            @AuthenticationPrincipal JwtUserInfo principal) {
        if (!"ADMIN".equals(principal.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<OrderResponse> orders = orderService.getFulfillmentQueue();
        return ResponseEntity.ok(orders);
    }

    // Platform-operations read model: orders stuck in the REFUND_FAILED
    // dead end (ADR-0034), needing manual review. Same role gate as
    // getFulfillmentQueue/shipOrder.
    @GetMapping("/refunds/failed")
    public ResponseEntity<List<OrderResponse>> getRefundFailedQueue(
            @AuthenticationPrincipal JwtUserInfo principal) {
        if (!"ADMIN".equals(principal.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<OrderResponse> orders = orderService.getRefundFailedQueue();
        return ResponseEntity.ok(orders);
    }

    // Platform-operations action: retries compensation for a REFUND_FAILED
    // order. Same role gate as shipOrder.
    @PostMapping("/{orderId}/retry-refund")
    public ResponseEntity<OrderResponse> retryRefund(
            @PathVariable String orderId,
            @AuthenticationPrincipal JwtUserInfo principal) {
        if (!"ADMIN".equals(principal.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        OrderResponse response = orderService.retryRefund(orderId);
        return ResponseEntity.ok(response);
    }
}