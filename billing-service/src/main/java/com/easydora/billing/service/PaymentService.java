package com.easydora.billing.service;

import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.messaging.events.PaymentProcessedEvent;
import com.easydora.billing.messaging.producer.PaymentEventProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentService {

    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private PaymentEventProducer paymentEventProducer;

    @Transactional
    public Payment processPayment(Long orderId, BigDecimal amount) {
        // Criar pagamento
        Payment payment = new Payment(orderId, amount);
        payment = paymentRepository.save(payment);
        
        // Lógica mock: valor par = aprova, ímpar = rejeita
        boolean isApproved = amount.remainder(BigDecimal.valueOf(2)).compareTo(BigDecimal.ZERO) == 0;
        
        if (isApproved) {
            payment.setStatus(PaymentStatus.APPROVED);
            payment.setTransactionId("TXN_" + UUID.randomUUID().toString().substring(0, 8));
            payment.setProcessedAt(LocalDateTime.now());
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Valor ímpar rejeitado pela política mock");
            payment.setProcessedAt(LocalDateTime.now());
        }
        
        payment = paymentRepository.save(payment);
        
        // Publicar evento
        PaymentProcessedEvent event = new PaymentProcessedEvent();
        event.setPaymentId(payment.getId());
        event.setOrderId(payment.getOrderId());
        event.setStatus(payment.getStatus().name());
        event.setAmount(payment.getAmount());
        event.setTransactionId(payment.getTransactionId());
        event.setFailureReason(payment.getFailureReason());
        event.setProcessedAt(payment.getProcessedAt());
        
        paymentEventProducer.sendPaymentProcessedEvent(event);
        
        return payment;
    }
    
    public Payment findById(Long id) {
        return paymentRepository.findById(id).orElse(null);
    }
    
    public Payment findByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }
}