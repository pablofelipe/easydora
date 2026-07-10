package com.easydora.billing.controller;

import com.easydora.billing.config.JwtAuthenticationFilter.JwtUserInfo;
import com.easydora.billing.dto.PaymentDTO;
import com.easydora.billing.service.PaymentService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    private boolean isOwnedBy(PaymentDTO payment, JwtUserInfo principal) {
        return payment.getUserId() != null && payment.getUserId().equals(principal.getUserId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentDTO> getPaymentById(@PathVariable Long id, @AuthenticationPrincipal JwtUserInfo principal) {
        try {
            PaymentDTO payment = paymentService.findById(id);
            if (!isOwnedBy(payment, principal)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            return ResponseEntity.ok(payment);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentDTO> getPaymentByOrderId(@PathVariable String orderId, @AuthenticationPrincipal JwtUserInfo principal) {
        try {
            PaymentDTO payment = paymentService.findByOrderId(orderId);
            if (!isOwnedBy(payment, principal)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            return ResponseEntity.ok(payment);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping
    public ResponseEntity<List<PaymentDTO>> getAllPayments(@AuthenticationPrincipal JwtUserInfo principal) {
        List<PaymentDTO> payments = paymentService.findAllForUser(principal.getUserId());
        return ResponseEntity.ok(payments);
    }
    
    @PostMapping("/process")
    public ResponseEntity<PaymentDTO> processPayment(
            @RequestParam String orderId,
            @RequestParam BigDecimal amount) {
        
        try {
            PaymentDTO payment = paymentService.processPayment(orderId, amount);
            return ResponseEntity.ok(payment);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    @PostMapping("/{orderId}/retry")
    public ResponseEntity<PaymentDTO> retryPayment(@PathVariable String orderId) {
        try {
            PaymentDTO payment = paymentService.retryPayment(orderId);
            return ResponseEntity.ok(payment);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    @PostMapping("/pending")
    public ResponseEntity<PaymentDTO> createPendingPayment(
            @RequestParam String orderId,
            @RequestParam BigDecimal amount) {
        
        try {
            // Para API REST, primeiro criamos o pagamento pendente
            // e depois processamos (isso poderia ser melhorado)
            PaymentDTO payment = paymentService.processPayment(orderId, amount);
            return ResponseEntity.ok(payment);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePayment(@PathVariable Long id, @AuthenticationPrincipal JwtUserInfo principal) {
        try {
            PaymentDTO payment = paymentService.findById(id);
            if (!isOwnedBy(payment, principal)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            paymentService.deletePayment(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}