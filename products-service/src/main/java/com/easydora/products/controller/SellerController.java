package com.easydora.products.controller;

import com.easydora.products.entity.Seller;
import com.easydora.products.repository.SellerRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/sellers")
public class SellerController {
    
    private final SellerRepository sellerRepository;
    
    public SellerController(SellerRepository sellerRepository) {
        this.sellerRepository = sellerRepository;
    }
    
    @GetMapping("/{userId}")
    public ResponseEntity<Seller> getSeller(@PathVariable String userId) {
        Optional<Seller> seller = sellerRepository.findById(userId);
        return seller.map(ResponseEntity::ok)
                   .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/{userId}/status")
    public ResponseEntity<Boolean> isActiveSeller(@PathVariable String userId) {
        boolean isActiveSeller = sellerRepository.existsByUserIdAndActiveTrue(userId);
        return ResponseEntity.ok(isActiveSeller);
    }
}