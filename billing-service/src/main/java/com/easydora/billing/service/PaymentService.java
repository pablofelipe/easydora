package com.easydora.billing.service;

import com.easydora.billing.dto.PaymentDTO;
import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.messaging.events.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PaymentService {
    
    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);
    
    private final PaymentRepository paymentRepository;
    
    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }
    
    // ========== MÉTODOS PARA EVENTOS KAFKA ==========
    
    @Transactional
    public void createPendingPayment(OrderCreatedEvent event) {
        try {

            String orderId = event.getOrderId();
            
            logger.info("Criando pagamento pendente para order: {}", orderId);

            // Verificar se já existe
            Optional<Payment> existingPayment = paymentRepository.findByOrderId(orderId);
            if (existingPayment.isPresent()) {
                logger.warn("Pagamento já existe para order: {}", orderId);
                return;
            }
            
            // Criar novo pagamento
            Payment payment = new Payment();
            payment.setOrderId(orderId);
            payment.setUserId(event.getUserId());
            payment.setAmount(event.getTotalAmount());
            payment.setStatus(PaymentStatus.PENDING);
            payment.setCreatedAt(LocalDateTime.now());
            payment.setTransactionId(UUID.randomUUID().toString());
            
            paymentRepository.save(payment);
            
            logger.info("Pagamento pendente criado: order={}, amount={}, transactionId={}",
                payment.getOrderId(), payment.getAmount(), payment.getTransactionId());

        } catch (Exception e) {
            logger.error("Erro ao criar pagamento pendente para order {}: {}",
                event.getOrderId(), e.getMessage(), e);
            throw e;
        }
    }
    
    public boolean checkIfPaymentExists(String orderId) {
        return paymentRepository.findByOrderId(orderId).isPresent();
    }
    
    // ========== MÉTODOS PARA API REST ==========
    
    public PaymentDTO findById(Long id) {
        return paymentRepository.findById(id)
            .map(this::convertToDTO)
            .orElseThrow(() -> new RuntimeException("Payment not found with id: " + id));
    }
    
    public PaymentDTO findByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId)
            .map(this::convertToDTO)
            .orElseThrow(() -> new RuntimeException("Payment not found for order: " + orderId));
    }
    
    public List<PaymentDTO> findAll() {
        return paymentRepository.findAll().stream()
            .map(this::convertToDTO)
            .collect(Collectors.toList());
    }
    
    @Transactional
    public PaymentDTO processPayment(String orderId, BigDecimal amount) {
        logger.info("Processando pagamento via API - Order: {}, Valor: {}", orderId, amount);
        
        // Buscar pagamento existente
        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(orderId);
        Payment payment;
        
        if (paymentOpt.isPresent()) {
            payment = paymentOpt.get();
            logger.info("Pagamento encontrado: {}", payment.getStatus());

            // Se já está aprovado, retornar
            if (payment.getStatus() == PaymentStatus.APPROVED) {
                logger.warn("Pagamento já APROVADO para order {}", orderId);
                return convertToDTO(payment);
            }
        } else {
            // Criar novo pagamento (fallback para chamada direta da API)
            logger.info("Criando novo pagamento (fallback API)");
            payment = new Payment();
            payment.setOrderId(orderId);
            payment.setAmount(amount);
            payment.setStatus(PaymentStatus.PENDING);
            payment.setCreatedAt(LocalDateTime.now());
            payment.setTransactionId(UUID.randomUUID().toString());
        }
        
        // Simular processamento de pagamento
        try {
            Thread.sleep(1000); // Simula processamento
            
            // Simulação: 90% de chance de aprovação
            boolean approved = Math.random() < 0.9;
            
            if (approved) {
                payment.setStatus(PaymentStatus.APPROVED);
                payment.setProcessedAt(LocalDateTime.now());
                logger.info("Pagamento APROVADO para order {}", orderId);
            } else {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason("Pagamento recusado pelo processador");
                payment.setProcessedAt(LocalDateTime.now());
                logger.warn("Pagamento FALHOU para order {}", orderId);
            }
            
            Payment savedPayment = paymentRepository.save(payment);
            return convertToDTO(savedPayment);
            
        } catch (Exception e) {
            logger.error("Erro ao processar pagamento para order {}: {}", orderId, e.getMessage());
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Erro interno: " + e.getMessage());
            payment.setProcessedAt(LocalDateTime.now());
            Payment savedPayment = paymentRepository.save(payment);
            return convertToDTO(savedPayment);
        }
    }
    
    @Transactional
    public PaymentDTO retryPayment(String orderId) {
        logger.info("Retentando pagamento para order: {}", orderId);
        
        Payment payment = paymentRepository.findByOrderId(orderId)
            .orElseThrow(() -> new RuntimeException("Payment not found for order: " + orderId));
        
        // Só pode retentar se falhou anteriormente
        if (payment.getStatus() != PaymentStatus.FAILED) {
            throw new RuntimeException("Cannot retry payment with status: " + payment.getStatus());
        }
        
        // Resetar para PENDING e tentar novamente
        payment.setStatus(PaymentStatus.PENDING);
        payment.setFailureReason(null);
        
        Payment savedPayment = paymentRepository.save(payment);
        
        // Processar pagamento
        return processPayment(orderId, payment.getAmount());
    }
    
    @Transactional
    public void deletePayment(Long id) {
        if (!paymentRepository.existsById(id)) {
            throw new RuntimeException("Payment not found with id: " + id);
        }
        paymentRepository.deleteById(id);
        logger.info("Payment deleted: {}", id);
    }
    
    // ========== MÉTODOS AUXILIARES ==========
    
    private PaymentDTO convertToDTO(Payment payment) {
        PaymentDTO dto = new PaymentDTO();
        dto.setId(payment.getId());
        dto.setOrderId(payment.getOrderId());
        dto.setUserId(payment.getUserId());
        dto.setAmount(payment.getAmount());
        dto.setStatus(payment.getStatus().name());
        dto.setTransactionId(payment.getTransactionId());
        dto.setFailureReason(payment.getFailureReason());
        dto.setCreatedAt(payment.getCreatedAt());
        dto.setProcessedAt(payment.getProcessedAt());
        return dto;
    }
}