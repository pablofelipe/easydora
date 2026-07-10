package com.easydora.billing.service;

import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationMessaging;
import com.easydora.billing.dto.PaymentDTO;
import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.messaging.events.OrderCreatedEvent;
import com.easydora.billing.messaging.events.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    private final RabbitTemplate rabbitTemplate;

    public PaymentService(PaymentRepository paymentRepository, RabbitTemplate rabbitTemplate) {
        this.paymentRepository = paymentRepository;
        this.rabbitTemplate = rabbitTemplate;
    }
    
    // ========== METHODS FOR ORDER-CREATED EVENTS (RabbitMQ) ==========

    @Transactional
    public void createPendingPayment(OrderCreatedEvent event) {
        try {

            String orderId = event.getOrderId();

            logger.info("Creating pending payment for order: {}", orderId);

            // Check whether it already exists
            Optional<Payment> existingPayment = paymentRepository.findByOrderId(orderId);
            if (existingPayment.isPresent()) {
                logger.warn("Payment already exists for order: {}", orderId);
                return;
            }

            // Create new payment
            Payment payment = new Payment();
            payment.setOrderId(orderId);
            payment.setUserId(event.getUserId());
            payment.setAmount(event.getTotalAmount());
            payment.setStatus(PaymentStatus.PENDING);
            payment.setCreatedAt(LocalDateTime.now());
            payment.setTransactionId(UUID.randomUUID().toString());
            
            paymentRepository.save(payment);
            
            logger.info("Pending payment created: order={}, amount={}, transactionId={}",
                payment.getOrderId(), payment.getAmount(), payment.getTransactionId());

        } catch (Exception e) {
            logger.error("Error creating pending payment for order {}: {}",
                event.getOrderId(), e.getMessage(), e);
            throw e;
        }
    }
    
    public boolean checkIfPaymentExists(String orderId) {
        return paymentRepository.findByOrderId(orderId).isPresent();
    }
    
    // ========== METHODS FOR REST API ==========
    
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
    
    public List<PaymentDTO> findAllForUser(Long userId) {
        return paymentRepository.findByUserId(userId).stream()
            .map(this::convertToDTO)
            .collect(Collectors.toList());
    }
    
    @Transactional
    public PaymentDTO processPayment(String orderId, BigDecimal amount) {
        logger.info("Processing payment via API - Order: {}, Amount: {}", orderId, amount);

        // Look up existing payment
        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(orderId);
        Payment payment;

        if (paymentOpt.isPresent()) {
            payment = paymentOpt.get();
            logger.info("Payment found: {}", payment.getStatus());

            // If already approved, return it
            if (payment.getStatus() == PaymentStatus.APPROVED) {
                logger.warn("Payment already APPROVED for order {}", orderId);
                return convertToDTO(payment);
            }
        } else {
            // Create new payment (fallback for a direct API call)
            logger.info("Creating new payment (API fallback)");
            payment = new Payment();
            payment.setOrderId(orderId);
            payment.setAmount(amount);
            payment.setStatus(PaymentStatus.PENDING);
            payment.setCreatedAt(LocalDateTime.now());
            payment.setTransactionId(UUID.randomUUID().toString());
        }
        
        // Simulate payment processing
        try {
            Thread.sleep(1000); // Simulate processing

            // Simulation: 90% chance of approval
            boolean approved = Math.random() < 0.9;

            if (approved) {
                payment.setStatus(PaymentStatus.APPROVED);
                payment.setProcessedAt(LocalDateTime.now());
                logger.info("Payment APPROVED for order {}", orderId);
            } else {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason("Payment declined by the processor");
                payment.setProcessedAt(LocalDateTime.now());
                logger.warn("Payment FAILED for order {}", orderId);
            }

            Payment savedPayment = paymentRepository.save(payment);
            publishPaymentEvent(savedPayment);
            return convertToDTO(savedPayment);

        } catch (Exception e) {
            logger.error("Error processing payment for order {}: {}", orderId, e.getMessage());
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Internal error: " + e.getMessage());
            payment.setProcessedAt(LocalDateTime.now());
            Payment savedPayment = paymentRepository.save(payment);
            publishPaymentEvent(savedPayment);
            return convertToDTO(savedPayment);
        }
    }

    /**
     * Publishes the payment outcome on order.exchange (payment.approved /
     * payment.failed) so orders-service can react via its own
     * PaymentEventsConsumer -- OrderService.handlePaymentReceived/
     * handlePaymentFailed already exist and already drive the state
     * machine into order.status-changed; this is the missing link that
     * finally calls them (see ADR-0001, finding 5, and ADR-0020's Roadmap
     * follow-up). Not called for a payment still PENDING -- only once it
     * has actually resolved to APPROVED or FAILED.
     */
    void publishPaymentEvent(Payment payment) {
        if (payment.getStatus() != PaymentStatus.APPROVED && payment.getStatus() != PaymentStatus.FAILED) {
            return;
        }

        PaymentEvent event = new PaymentEvent();
        event.setOrderId(payment.getOrderId());
        event.setTransactionId(payment.getTransactionId());
        event.setFailureReason(payment.getFailureReason());

        String routingKey = payment.getStatus() == PaymentStatus.APPROVED
                ? RabbitMQConfig.PAYMENT_APPROVED_KEY
                : RabbitMQConfig.PAYMENT_FAILED_KEY;

        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, routingKey, event, CorrelationMessaging.withCorrelation());
        BusinessEventLog.info(logger, routingKey + ".published", payment.getOrderId(), "PaymentEvent published");
    }
    
    @Transactional
    public PaymentDTO retryPayment(String orderId) {
        logger.info("Retrying payment for order: {}", orderId);

        Payment payment = paymentRepository.findByOrderId(orderId)
            .orElseThrow(() -> new RuntimeException("Payment not found for order: " + orderId));

        // Can only retry if it previously failed
        if (payment.getStatus() != PaymentStatus.FAILED) {
            throw new RuntimeException("Cannot retry payment with status: " + payment.getStatus());
        }

        // Reset to PENDING and try again
        payment.setStatus(PaymentStatus.PENDING);
        payment.setFailureReason(null);

        Payment savedPayment = paymentRepository.save(payment);

        // Process payment
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
    
    // ========== HELPER METHODS ==========
    
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