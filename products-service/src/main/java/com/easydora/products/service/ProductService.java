package com.easydora.products.service;

import com.easydora.products.dto.ProductRequest;
import com.easydora.products.dto.ProductResponse;
import com.easydora.products.entity.Product;
import com.easydora.products.entity.Seller;
import com.easydora.products.entity.UserRole;
import com.easydora.products.event.ProductCreatedEvent;
import com.easydora.products.event.ProductDeletedEvent;
import com.easydora.products.event.ProductUpdatedEvent;
import com.easydora.products.repository.ProductRepository;
import com.easydora.products.repository.SellerRepository;
import com.easydora.products.exception.*;

import com.easydora.products.config.RabbitMQConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final SellerRepository sellerRepository;
    private final RabbitTemplate rabbitTemplate;

    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    public ProductService(ProductRepository productRepository, SellerRepository sellerRepository, RabbitTemplate rabbitTemplate) {
        this.productRepository = productRepository;
        this.sellerRepository = sellerRepository;
        this.rabbitTemplate = rabbitTemplate;
    }
    
    public ProductResponse createProduct(ProductRequest request, String sellerId) {
        
        if (sellerId == null || sellerId.trim().isEmpty()) {
            throw new UserNotFoundException("User ID cannot be null or empty");
        }
            
        if (request.getInitialStock() == null || request.getInitialStock() < 0) {
            throw new InvalidProductException("Initial stock must be specified and non-negative");
        }
        
        Seller seller = sellerRepository.findById(sellerId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + sellerId));
            
        if (!UserRole.SELLER.equals(seller.getRole())) {
            throw new InvalidUserRoleException("User is not a SELLER: " + sellerId);
        }
        
        if (!seller.getActive()) {
            throw new SellerNotActiveException("Seller is not active: " + sellerId);
        }
        
        Product product = new Product();
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setSeller(seller);
        product.setActive(true);
        
        Product savedProduct = productRepository.save(product);

        publishProductCreatedEvent(savedProduct, request.getInitialStock());

        return mapToProductResponse(savedProduct);
    }
    
    private void publishProductCreatedEvent(Product product, Integer initialStock) {
        try {
            ProductCreatedEvent event = new ProductCreatedEvent();
            event.setProductId(product.getId().toString());
            event.setProductName(product.getName());
            event.setSellerId(product.getSeller().getUserId());
            event.setPrice(product.getPrice());
            event.setInitialStock(initialStock);
            event.setCreatedAt(Instant.now().toString());

            rabbitTemplate.convertAndSend(RabbitMQConfig.PRODUCT_EXCHANGE, RabbitMQConfig.PRODUCT_CREATED_KEY, event);
            logger.info("ProductCreatedEvent published successfully - Product: {}", product.getId());

        } catch (Exception e) {
            logger.error("Error publishing ProductCreatedEvent for product: {}", product.getId(), e);
        }
    }
    
    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts() {
        return productRepository.findByActiveTrue()
            .stream()
            .map(this::mapToProductResponse)
            .collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public ProductResponse getProductById(String id) {
        Product product = productRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ProductNotFoundException("Product not found: " + id));
        return mapToProductResponse(product);
    }
    
    @Transactional(readOnly = true)
    public List<ProductResponse> getProductsBySeller(String sellerId) {
        return productRepository.findBySellerUserIdAndActiveTrue(sellerId)
            .stream()
            .map(this::mapToProductResponse)
            .collect(Collectors.toList());
    }
    
    public ProductResponse updateProduct(String id, ProductRequest request, String sellerId) {
        
        if (sellerId == null || sellerId.trim().isEmpty()) {
            throw new UserNotFoundException("User ID cannot be null or empty");
        }
        
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new ProductNotFoundException("Product not found: " + id));
            
        if (!product.getSeller().getUserId().equals(sellerId)) {
            throw new UnauthorizedException("Not authorized to update this product");
        }

        Seller seller = sellerRepository.findById(sellerId)
            .orElseThrow(() -> new UserNotFoundException("Seller not found: " + sellerId));
            
        if (!seller.getActive() || !UserRole.SELLER.equals(seller.getRole())) {
            throw new SellerNotActiveException("Seller is no longer active or not a SELLER");
        }
        
        if (request.getName() != null) {
            product.setName(request.getName());
        }
        if (request.getDescription() != null) {
            product.setDescription(request.getDescription());
        }
        product.setPrice(request.getPrice());
        
        Product updatedProduct = productRepository.save(product);

        publishProductUpdatedEvent(updatedProduct);

        return mapToProductResponse(updatedProduct);
    }
    
    private void publishProductUpdatedEvent(Product product) {
        try {
            ProductUpdatedEvent event = new ProductUpdatedEvent();
            event.setProductId(product.getId().toString());
            event.setProductName(product.getName());
            event.setPrice(product.getPrice());
            event.setActive(product.getActive());
            event.setUpdatedAt(Instant.now().toString());

            rabbitTemplate.convertAndSend(RabbitMQConfig.PRODUCT_EXCHANGE, RabbitMQConfig.PRODUCT_UPDATED_KEY, event);
            logger.info("ProductUpdatedEvent published successfully - Product: {}", product.getId());

        } catch (Exception e) {
            logger.error("Error publishing ProductUpdatedEvent for product: {}", product.getId(), e);
        }
    }

    public void deleteProduct(String id, String sellerId) {

        if (sellerId == null || sellerId.trim().isEmpty()) {
            throw new UserNotFoundException("User ID cannot be null or empty");
        }
        
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new ProductNotFoundException("Product not found: " + id));
            
        if (!product.getSeller().getUserId().equals(sellerId)) {
            throw new UnauthorizedException("Not authorized to delete this product");
        }
        
        product.setActive(false);
        Product deletedProduct = productRepository.save(product);

        publishProductDeletedEvent(deletedProduct);
    }
    
    private void publishProductDeletedEvent(Product product) {
        try {
            ProductDeletedEvent event = new ProductDeletedEvent();
            event.setProductId(product.getId().toString());
            event.setDeletedAt(Instant.now().toString());

            rabbitTemplate.convertAndSend(RabbitMQConfig.PRODUCT_EXCHANGE, RabbitMQConfig.PRODUCT_DELETED_KEY, event);
            logger.info("ProductDeletedEvent published successfully - Product: {}", product.getId());

        } catch (Exception e) {
            logger.error("Error publishing ProductDeletedEvent for product: {}", product.getId(), e);
        }
    }

    private ProductResponse mapToProductResponse(Product product) {
        ProductResponse response = new ProductResponse();
        response.setId(product.getId());
        response.setName(product.getName());
        response.setDescription(product.getDescription());
        response.setPrice(product.getPrice());
        response.setActive(product.getActive());
        response.setCreatedAt(product.getCreatedAt());
        response.setUpdatedAt(product.getUpdatedAt());
        
        Seller seller = product.getSeller();
        ProductResponse.SellerInfo sellerInfo = new ProductResponse.SellerInfo(
            seller.getUserId(),
            seller.getName(),
            seller.getAvatarUrl()
        );
        response.setSeller(sellerInfo);
        
        return response;
    }
}