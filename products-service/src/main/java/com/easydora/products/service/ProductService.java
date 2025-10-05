package com.easydora.products.service;

import com.easydora.products.dto.ProductRequest;
import com.easydora.products.dto.ProductResponse;
import com.easydora.products.entity.Product;
import com.easydora.products.entity.Seller;
import com.easydora.products.entity.UserRole;
import com.easydora.products.repository.ProductRepository;
import com.easydora.products.repository.SellerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProductService {
    
    private final ProductRepository productRepository;
    private final SellerRepository sellerRepository;
    
    public ProductService(ProductRepository productRepository, SellerRepository sellerRepository) {
        this.productRepository = productRepository;
        this.sellerRepository = sellerRepository;
    }
    
    public ProductResponse createProduct(ProductRequest request, String sellerId) {
        Seller seller = sellerRepository.findById(sellerId)
            .orElseThrow(() -> new RuntimeException("User not found: " + sellerId));
            
        if (!UserRole.SELLER.equals(seller.getRole())) {
            throw new RuntimeException("User is not a SELLER: " + sellerId);
        }
        
        if (!seller.getActive()) {
            throw new RuntimeException("Seller is not active: " + sellerId);
        }
        
        Product product = new Product();
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStockQuantity(request.getStockQuantity());
        product.setSeller(seller);
        product.setActive(true);
        
        Product savedProduct = productRepository.save(product);
        return mapToProductResponse(savedProduct);
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
            .orElseThrow(() -> new RuntimeException("Product not found: " + id));
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
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Product not found: " + id));
            
        if (!product.getSeller().getUserId().equals(sellerId)) {
            throw new RuntimeException("Not authorized to update this product");
        }

        if (!product.getSeller().getUserId().equals(sellerId)) {
            throw new RuntimeException("Not authorized to update this product");
        }

        Seller seller = sellerRepository.findById(sellerId)
            .orElseThrow(() -> new RuntimeException("Seller not found: " + sellerId));
            
        if (!seller.getActive() || !UserRole.SELLER.equals(seller.getRole())) {
            throw new RuntimeException("Seller is no longer active or not a SELLER");
        }
        
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStockQuantity(request.getStockQuantity());
        
        Product updatedProduct = productRepository.save(product);
        return mapToProductResponse(updatedProduct);
    }
    
    public void deleteProduct(String id, String sellerId) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Product not found: " + id));
            
        if (!product.getSeller().getUserId().equals(sellerId)) {
            throw new RuntimeException("Not authorized to delete this product");
        }
        
        product.setActive(false);
        productRepository.save(product);
    }
    
    private ProductResponse mapToProductResponse(Product product) {
        ProductResponse response = new ProductResponse();
        response.setId(product.getId());
        response.setName(product.getName());
        response.setDescription(product.getDescription());
        response.setPrice(product.getPrice());
        response.setStockQuantity(product.getStockQuantity());
        response.setActive(product.getActive());
        response.setCreatedAt(product.getCreatedAt());
        response.setUpdatedAt(product.getUpdatedAt());
        
        // Map seller information
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
