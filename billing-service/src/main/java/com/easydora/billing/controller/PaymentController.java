package com.easydora.billing.controller;

import com.easydora.billing.model.Payment;
import com.easydora.billing.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    @GetMapping("/{id}")
    public ResponseEntity<Payment> getPayment(@PathVariable Long id) {
        Payment payment = paymentService.findById(id);
        return payment != null ? ResponseEntity.ok(payment) : ResponseEntity.notFound().build();
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<Payment> getPaymentByOrderId(@PathVariable Long orderId) {
        Payment payment = paymentService.findByOrderId(orderId);
        return payment != null ? ResponseEntity.ok(payment) : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Payment> retryPayment(@PathVariable Long id) {
        Payment payment = paymentService.findById(id);
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }
        
        // Lógica de retry (simplificada)
        Payment retriedPayment = paymentService.processPayment(payment.getOrderId(), payment.getAmount());
        return ResponseEntity.ok(retriedPayment);
    }
}