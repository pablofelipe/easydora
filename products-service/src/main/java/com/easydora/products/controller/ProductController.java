package com.easydora.products.controller;

import com.easydora.products.config.JwtAuthenticationFilter.JwtUserInfo;
import com.easydora.products.dto.ProductRequest;
import com.easydora.products.dto.ProductResponse;
import com.easydora.products.service.ProductService;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class ProductController {

    private final ProductService productService;
    private final DataSource dataSource;
    private static final Logger logger = LoggerFactory.getLogger(ProductController.class);

    public ProductController(ProductService productService, DataSource dataSource) {
        this.productService = productService;
        this.dataSource = dataSource;
    }

    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        Map<String, String> response = new HashMap<>();
        response.put("message", "Products service is running!");
        response.put("status", "OK");
        response.put("schema", "products_schema");
        return ResponseEntity.ok(response);
    }

    // A short-timeout, real connectivity probe (see ADR-0010's residual
    // gap: this endpoint -- the one Docker's own HEALTHCHECK and the
    // Gateway route hit -- used to hardcode a claim about the database
    // without ever checking it). 2s is generous against this project's own
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
    public ResponseEntity<Map<String, Object>> health() {
        boolean databaseReachable = isDatabaseReachable();

        Map<String, Object> health = new HashMap<>();
        health.put("status", databaseReachable ? "OK" : "DOWN");
        health.put("service", "products-service");
        health.put("schema", "products_schema");
        health.put("database", databaseReachable ? "Connected" : "Disconnected");

        return databaseReachable
            ? ResponseEntity.ok(health)
            : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(health);
    }

    @PostMapping("/createProduct")
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody ProductRequest request,
            @AuthenticationPrincipal JwtUserInfo principal) {

        String sellerId = principal.getUserId().toString();
        logger.info("Received createProduct request - Seller: {}, Product: {}", sellerId, request.getName());
        logger.info("Request body: {}", request);

        ProductResponse response = productService.createProduct(request, sellerId);
        logger.info("Product created successfully - ID: {}", response.getId());
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/all-products")
    public ResponseEntity<List<ProductResponse>> getAllProducts() {
        logger.info("Fetching all active products");
        List<ProductResponse> products = productService.getAllProducts();
        logger.info("Found {} active products", products.size());
        return ResponseEntity.ok(products);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable String id) {
        logger.info("Fetching product by ID: {}", id);
        ProductResponse product = productService.getProductById(id);
        logger.info("Product found: {}", product.getName());
        return ResponseEntity.ok(product);
    }
    
    @GetMapping("/seller/{sellerId}")
    public ResponseEntity<List<ProductResponse>> getProductsBySeller(@PathVariable String sellerId) {
        logger.info("Fetching products for seller: {}", sellerId);
        List<ProductResponse> products = productService.getProductsBySeller(sellerId);
        logger.info("Found {} products for seller {}", products.size(), sellerId);
        return ResponseEntity.ok(products);
    }
    
    @GetMapping("/my-products")
    public ResponseEntity<List<ProductResponse>> getMyProducts(@AuthenticationPrincipal JwtUserInfo principal) {
        String sellerId = principal.getUserId().toString();
        logger.info("Fetching products for current seller: {}", sellerId);
        List<ProductResponse> products = productService.getProductsBySeller(sellerId);
        logger.info("Found {} products for current seller", products.size());
        return ResponseEntity.ok(products);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable String id,
            @Valid @RequestBody ProductRequest request,
            @AuthenticationPrincipal JwtUserInfo principal) {

        String sellerId = principal.getUserId().toString();
        logger.info("Updating product {} for seller {}", id, sellerId);
        ProductResponse response = productService.updateProduct(id, request, sellerId);
        logger.info("Product {} updated successfully", id);
        return ResponseEntity.ok(response);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(
            @PathVariable String id,
            @AuthenticationPrincipal JwtUserInfo principal) {

        String sellerId = principal.getUserId().toString();
        logger.info("Deleting product {} for seller {}", id, sellerId);
        productService.deleteProduct(id, sellerId);
        logger.info("Product {} deleted successfully", id);
        return ResponseEntity.noContent().build();
    }
}