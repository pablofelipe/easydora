package com.easydora.billing.service;

import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.service.provider.PaymentProvider;
import com.easydora.billing.service.provider.PaymentResult;
import com.easydora.billing.messaging.events.PaymentProcessedEvent;
import com.easydora.billing.messaging.producer.PaymentEventProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class PaymentService {

    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private PaymentEventProducer paymentEventProducer;
    
    @Autowired
    private PaymentProvider paymentProvider; // Pode injetar o mock como padrão

    @Transactional
    public Payment processPayment(Long orderId, BigDecimal amount) {
        Payment payment = new Payment(orderId, amount);
        payment = paymentRepository.save(payment);
        
        // Delegar para o provedor
        PaymentResult result = paymentProvider.processPayment(orderId, amount);
        
        // Processar resultado
        if (result.isApproved()) {
            payment.setStatus(PaymentStatus.APPROVED);
            payment.setTransactionId(result.getTransactionId());
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(result.getFailureReason());
        }
        
        payment.setProcessedAt(LocalDateTime.now());
        payment = paymentRepository.save(payment);
        
        // Publicar evento
        publishPaymentEvent(payment);
        
        return payment;
    }

    public Optional<Payment> findById(Long id) {
        return paymentRepository.findById(id);
    }
    
    public Payment findByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    private void publishPaymentEvent(Payment payment) {
        PaymentProcessedEvent event = new PaymentProcessedEvent();
        event.setPaymentId(payment.getId());
        event.setOrderId(payment.getOrderId());
        event.setStatus(payment.getStatus().name());
        event.setAmount(payment.getAmount());
        event.setTransactionId(payment.getTransactionId());
        event.setFailureReason(payment.getFailureReason());
        event.setProcessedAt(payment.getProcessedAt());
        
        paymentEventProducer.sendPaymentProcessedEvent(event);
    }
}