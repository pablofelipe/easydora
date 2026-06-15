package com.easydora.billing.controller;

import com.easydora.billing.dto.PaymentDTO;
import com.easydora.billing.service.PaymentService;

import org.apache.kafka.common.protocol.types.Field.Str;
import org.springframework.http.ResponseEntity;
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
    
    @GetMapping("/{id}")
    public ResponseEntity<PaymentDTO> getPaymentById(@PathVariable Long id) {
        try {
            PaymentDTO payment = paymentService.findById(id);
            return ResponseEntity.ok(payment);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
    
    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentDTO> getPaymentByOrderId(@PathVariable String orderId) {
        try {
            PaymentDTO payment = paymentService.findByOrderId(orderId);
            return ResponseEntity.ok(payment);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
    
    @GetMapping
    public ResponseEntity<List<PaymentDTO>> getAllPayments() {
        List<PaymentDTO> payments = paymentService.findAll();
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
    public ResponseEntity<Void> deletePayment(@PathVariable Long id) {
        try {
            paymentService.deletePayment(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}