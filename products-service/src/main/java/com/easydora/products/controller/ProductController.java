package com.easydora.products.controller;

import com.easydora.products.dto.ProductRequest;
import com.easydora.products.dto.ProductResponse;
import com.easydora.products.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class ProductController {
    
    private final ProductService productService;
    private static final Logger logger = LoggerFactory.getLogger(ProductController.class);

    public ProductController(ProductService productService) {
        this.productService = productService;
    }
    
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        Map<String, String> response = new HashMap<>();
        response.put("message", "Products service is running!");
        response.put("status", "OK");
        response.put("schema", "products_schema");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "OK");
        health.put("service", "products-service");
        health.put("schema", "products_schema");
        health.put("database", "Connected");
        
        return ResponseEntity.ok(health);
    }

    @PostMapping("/createProduct")
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody ProductRequest request,
            @RequestHeader("X-User-Id") String sellerId) {

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
    public ResponseEntity<List<ProductResponse>> getMyProducts(@RequestHeader("X-User-Id") String sellerId) {
        logger.info("Fetching products for current seller: {}", sellerId);
        List<ProductResponse> products = productService.getProductsBySeller(sellerId);
        logger.info("Found {} products for current seller", products.size());
        return ResponseEntity.ok(products);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable String id,
            @Valid @RequestBody ProductRequest request,
            @RequestHeader("X-User-Id") String sellerId) {
        
        logger.info("Updating product {} for seller {}", id, sellerId);
        ProductResponse response = productService.updateProduct(id, request, sellerId);
        logger.info("Product {} updated successfully", id);
        return ResponseEntity.ok(response);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String sellerId) {
        
        logger.info("Deleting product {} for seller {}", id, sellerId);
        productService.deleteProduct(id, sellerId);
        logger.info("Product {} deleted successfully", id);
        return ResponseEntity.noContent().build();
    }
}