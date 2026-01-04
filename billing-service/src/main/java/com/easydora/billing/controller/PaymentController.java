package com.easydora.billing.controller;

import com.easydora.billing.model.Payment;
import com.easydora.billing.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    @GetMapping("/{id}")
    public ResponseEntity<Payment> getPayment(@PathVariable Long id) {
        return paymentService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<Payment> getPaymentByOrderId(@PathVariable Long orderId) {
        Payment payment = paymentService.findByOrderId(orderId);
        return payment != null ? ResponseEntity.ok(payment) : ResponseEntity.notFound().build();
    }

    @GetMapping
    public List<Payment> getAllPayments() {
        return paymentService.findAll();
    }

    @PostMapping("/process")
    public ResponseEntity<Payment> processPayment(
            @RequestParam Long orderId,
            @RequestParam BigDecimal amount) {
        
        try {
            Payment payment = paymentService.processPayment(orderId, amount);
            return ResponseEntity.ok(payment);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Payment> retryPayment(@PathVariable Long id) {
        try {
            Payment retriedPayment = paymentService.retryPayment(id);
            return ResponseEntity.ok(retriedPayment);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
    
    // Métodos para testes
    @PostMapping("/create-pending")
    public ResponseEntity<Payment> createPendingPayment(
            @RequestParam Long orderId,
            @RequestParam BigDecimal amount) {
        
        try {
            Payment payment = paymentService.createPendingPayment(orderId, amount);
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