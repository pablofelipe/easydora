package com.easydora.billing.service;

import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class PaymentService {
    
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    // ========== MÉTODOS PARA O CONTROLLER ==========
    
    public Optional<Payment> findById(Long id) {
        log.debug("Buscando pagamento por ID: {}", id);
        return paymentRepository.findById(id);
    }
    
    public Payment findByOrderId(Long orderId) {
        log.debug("Buscando pagamento por Order ID: {}", orderId);
        // Retorna null se não encontrar, para compatibilidade com seu controller
        return paymentRepository.findByOrderId(orderId).orElse(null);
    }
    
    public List<Payment> findAll() {
        return paymentRepository.findAll();
    }
    
    public Payment save(Payment payment) {
        return paymentRepository.save(payment);
    }
    
    // ========== VERSÕES DE PROCESSAMENTO DE PAGAMENTO ==========
    
    /**
     * Versão usada pelo Controller (API REST) - processa e salva no banco
     */
    public Payment processPayment(Long orderId, BigDecimal amount) {
        log.info("💳 Processando pagamento via API - Pedido: {}, Valor: {}", orderId, amount);
        
        // Verifica se já existe pagamento para este pedido
        Optional<Payment> existingPayment = paymentRepository.findByOrderId(orderId);
        if (existingPayment.isPresent()) {
            log.warn("⚠️ Pagamento já existe para pedido {}", orderId);
            return existingPayment.get();
        }
        
        // Cria novo pagamento
        Payment payment = new Payment(orderId, amount);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setCreatedAt(LocalDateTime.now());
        
        // Simula processamento
        PaymentResult result = processPayment(orderId.toString(), "API-CLIENT", amount);
        
        // Atualiza status com base no resultado
        if (result.isSuccess()) {
            payment.setStatus(PaymentStatus.APPROVED);
            payment.setTransactionId(result.getTransactionId());
            payment.setProcessedAt(LocalDateTime.now());
            log.info("✅ Pagamento APROVADO para pedido {}", orderId);
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(result.getErrorMessage());
            payment.setProcessedAt(LocalDateTime.now());
            log.warn("❌ Pagamento FALHOU para pedido {}", orderId);
        }
        
        // Salva no banco
        return paymentRepository.save(payment);
    }
    
    /**
     * Versão usada pelo Kafka Consumer - apenas processa e retorna resultado
     */
    public PaymentResult processPayment(String orderId, String customerId, BigDecimal amount) {
        log.info("💳 Processando pagamento - Pedido: {}, Cliente: {}, Valor: {}", 
            orderId, customerId, amount);
        
        PaymentResult result = new PaymentResult();
        
        try {
            // Verifica se já existe pagamento no banco
            Long orderIdLong = Long.parseLong(orderId);
            Optional<Payment> existingPayment = paymentRepository.findByOrderId(orderIdLong);
            if (existingPayment.isPresent()) {
                log.warn("⚠️ Pagamento já processado para pedido {}", orderId);
                
                Payment payment = existingPayment.get();
                result.setSuccess(payment.getStatus() == PaymentStatus.APPROVED);
                result.setTransactionId(payment.getTransactionId());
                result.setErrorMessage("Payment already exists with status: " + payment.getStatus());
                return result;
            }
            
            // Simulação de gateway de pagamento
            boolean paymentApproved = simulatePaymentGateway(customerId, amount);
            
            if (paymentApproved) {
                result.setSuccess(true);
                result.setTransactionId(generateTransactionId(orderId));
                log.info("🎉 Pagamento APROVADO para pedido {}", orderId);
                
                // Salva no banco
                Payment payment = new Payment(orderIdLong, amount);
                payment.setStatus(PaymentStatus.APPROVED);
                payment.setTransactionId(result.getTransactionId());
                payment.setProcessedAt(LocalDateTime.now());
                paymentRepository.save(payment);
                
            } else {
                result.setSuccess(false);
                result.setErrorMessage("Fundos insuficientes ou cartão recusado");
                log.warn("💔 Pagamento RECUSADO para pedido {}", orderId);
                
                // Salva falha no banco
                Payment payment = new Payment(orderIdLong, amount);
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(result.getErrorMessage());
                payment.setProcessedAt(LocalDateTime.now());
                paymentRepository.save(payment);
            }
            
        } catch (Exception e) {
            log.error("🔥 Erro no processamento do pagamento para pedido {}: {}", 
                orderId, e.getMessage(), e);
            result.setSuccess(false);
            result.setErrorMessage("Erro no gateway de pagamento: " + e.getMessage());
        }
        
        return result;
    }
    
    // ========== MÉTODOS AUXILIARES ==========
    
    private String generateTransactionId(String orderId) {
        return "TX-" + System.currentTimeMillis() + "-" + orderId;
    }
    
    private boolean simulatePaymentGateway(String customerId, BigDecimal amount) {
        // Lógica simulada de pagamento
        double approvalRate = 0.85; // 85% de aprovação
        
        // Ajustes baseados no valor
        if (amount.compareTo(new BigDecimal("5000")) > 0) {
            approvalRate = 0.60; // Valores altos: 60% de aprovação
        } else if (amount.compareTo(new BigDecimal("1000")) > 0) {
            approvalRate = 0.75; // Valores médios: 75% de aprovação
        }
        
        // Clientes VIP têm maior chance
        if (customerId.contains("777") || customerId.contains("888") || 
            customerId.contains("999") || customerId.endsWith("1")) {
            approvalRate += 0.15; // +15% para clientes especiais
        }
        
        boolean approved = Math.random() < approvalRate;
        
        log.debug("Simulação gateway: customer={}, amount={}, rate={}, approved={}",
            customerId, amount, approvalRate, approved);
        
        return approved;
    }
    
    // ========== MÉTODOS ADICIONAIS PARA O CONTROLLER ==========
    
    /**
     * Retry payment - método usado pelo controller
     */
    public Payment retryPayment(Long paymentId) {
        log.info("🔄 Tentando reprocessar pagamento ID: {}", paymentId);
        
        Optional<Payment> paymentOpt = paymentRepository.findById(paymentId);
        if (paymentOpt.isEmpty()) {
            throw new RuntimeException("Pagamento não encontrado com ID: " + paymentId);
        }
        
        Payment payment = paymentOpt.get();
        
        // Cria novo resultado de pagamento
        PaymentResult result = processPayment(
            payment.getOrderId().toString(),
            "RETRY-CLIENT-" + payment.getId(),
            payment.getAmount()
        );
        
        // Atualiza o pagamento existente
        if (result.isSuccess()) {
            payment.setStatus(PaymentStatus.APPROVED);
            payment.setTransactionId(result.getTransactionId());
            payment.setFailureReason(null);
            payment.setProcessedAt(LocalDateTime.now());
            log.info("✅ Retry bem-sucedido para pagamento ID: {}", paymentId);
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(result.getErrorMessage());
            payment.setProcessedAt(LocalDateTime.now());
            log.warn("❌ Retry falhou para pagamento ID: {}", paymentId);
        }
        
        return paymentRepository.save(payment);
    }
    
    /**
     * Cria pagamento pendente (para testes)
     */
    public Payment createPendingPayment(Long orderId, BigDecimal amount) {
        Payment payment = new Payment(orderId, amount);
        payment.setStatus(PaymentStatus.PENDING);
        return paymentRepository.save(payment);
    }
    
    /**
     * Atualiza status do pagamento
     */
    public Payment updatePaymentStatus(Long paymentId, PaymentStatus status, String transactionId) {
        Optional<Payment> paymentOpt = paymentRepository.findById(paymentId);
        if (paymentOpt.isEmpty()) {
            throw new RuntimeException("Pagamento não encontrado");
        }
        
        Payment payment = paymentOpt.get();
        payment.setStatus(status);
        payment.setTransactionId(transactionId);
        payment.setProcessedAt(LocalDateTime.now());
        
        return paymentRepository.save(payment);
    }
    
    /**
     * Deleta pagamento (apenas para admin/testes)
     */
    public void deletePayment(Long paymentId) {
        if (paymentRepository.existsById(paymentId)) {
            paymentRepository.deleteById(paymentId);
            log.info("🗑️ Pagamento deletado: {}", paymentId);
        } else {
            log.warn("⚠️ Pagamento não encontrado para deleção: {}", paymentId);
        }
    }
}