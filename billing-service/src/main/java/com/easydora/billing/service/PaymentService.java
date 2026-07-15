package com.easydora.billing.service;

import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationMessaging;
import com.easydora.billing.dto.PaymentDTO;
import com.easydora.billing.exception.PaymentNotFoundException;
import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.messaging.events.OrderCreatedEvent;
import com.easydora.billing.messaging.events.PaymentEvent;
import com.easydora.billing.service.provider.PaymentProvider;
import com.easydora.billing.service.provider.PaymentResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final PaymentProvider paymentProvider;
    private final Counter paymentsApprovedCounter;
    private final Counter paymentsFailedCounter;

    public PaymentService(PaymentRepository paymentRepository, RabbitTemplate rabbitTemplate,
            PaymentProvider paymentProvider, MeterRegistry meterRegistry) {
        this.paymentRepository = paymentRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.paymentProvider = paymentProvider;
        // Business metrics (ADR-0036): infra-level metrics already answer
        // "is the system healthy"; these answer a question infra can't --
        // how much of the payment volume actually succeeds.
        this.paymentsApprovedCounter = meterRegistry.counter("payments_approved_total");
        this.paymentsFailedCounter = meterRegistry.counter("payments_failed_total");
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
    public PaymentDTO processPayment(String orderId) {
        logger.info("Processing payment via API - Order: {}", orderId);

        // A Payment row is only ever created by createPendingPayment,
        // reacting to order.created -- there is exactly one way for a
        // Payment to come into existence. Its absence here is a domain
        // error, not an opportunity to create one on the fly.
        Payment payment = paymentRepository.findByOrderId(orderId)
            .orElseThrow(() -> new PaymentNotFoundException(orderId));
        logger.info("Payment found: {}", payment.getStatus());

        // If already approved, return it
        if (payment.getStatus() == PaymentStatus.APPROVED) {
            logger.warn("Payment already APPROVED for order {}", orderId);
            return convertToDTO(payment);
        }

        BigDecimal amount = payment.getAmount();

        // Every approval decision lives inside PaymentProvider -- this
        // method only reacts to the result, never decides on its own. Only
        // a failure from the provider call itself is translated into a
        // FAILED payment here; anything that goes wrong afterwards
        // (persisting the decision, publishing it) must propagate as-is,
        // never be reinterpreted as the provider having declined the
        // charge -- see the "Internal error" catch below.
        try {
            PaymentResult result = paymentProvider.processPayment(orderId, amount);

            if (result.isApproved()) {
                payment.setStatus(PaymentStatus.APPROVED);
                payment.setTransactionId(result.getTransactionId());
                payment.setProcessedAt(LocalDateTime.now());
                logger.info("Payment APPROVED for order {}", orderId);
            } else {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(result.getFailureReason());
                payment.setProcessedAt(LocalDateTime.now());
                logger.warn("Payment FAILED for order {}", orderId);
            }
        } catch (Exception e) {
            logger.error("Error processing payment for order {}: {}", orderId, e.getMessage());
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Internal error: " + e.getMessage());
            payment.setProcessedAt(LocalDateTime.now());
        }

        // saveAndFlush (ADR-0033): forces the version check here, before
        // publishPaymentEvent -- a conflict must never be discovered after
        // the outcome has already been published. A failure at or after
        // this point (including publishPaymentEvent) propagates as-is and
        // rolls back this transaction -- it must never silently overwrite
        // an already-decided outcome with a wrong one.
        Payment savedPayment = paymentRepository.saveAndFlush(payment);
        publishPaymentEvent(savedPayment);
        return convertToDTO(savedPayment);
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

        if (payment.getStatus() == PaymentStatus.APPROVED) {
            paymentsApprovedCounter.increment();
        } else {
            paymentsFailedCounter.increment();
        }
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

        // Reset to PENDING and try again. saveAndFlush (ADR-0033): a
        // conflicting retry must fail fast here, before ever reaching
        // processPayment.
        payment.setStatus(PaymentStatus.PENDING);
        payment.setFailureReason(null);

        paymentRepository.saveAndFlush(payment);

        // Process payment
        return processPayment(orderId);
    }
    
    // ADR-0034: reacts to a RefundPaymentCommand from orders-service.
    // Billing is the sole decider here -- Orders only published intent,
    // never touched this entity directly. Idempotent: a
    // redelivered/duplicate command for a Payment already REFUNDED is a
    // no-op, not an error (no second provider call, no second publish).
    // payment.refund.failed covers both a genuine provider decline and a
    // precondition Billing itself found unmet (Payment missing / not
    // APPROVED) -- the latter is an architectural inconsistency (this
    // command should only ever arrive for a Payment this service already
    // approved), never expected in normal operation; the reason text
    // distinguishes the two for whoever investigates.
    @Transactional
    public void refundPayment(String orderId) {
        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(orderId);

        if (paymentOpt.isEmpty()) {
            logger.error("Refund requested for order {} but no Payment exists", orderId);
            publishRefundFailed(orderId, "Payment not found for order " + orderId);
            return;
        }

        Payment payment = paymentOpt.get();

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            logger.info("Refund already completed for order {} -- skipping (idempotent)", orderId);
            return;
        }

        if (payment.getStatus() != PaymentStatus.APPROVED) {
            logger.error("Refund requested for order {} but Payment is {} (expected APPROVED)",
                orderId, payment.getStatus());
            publishRefundFailed(orderId,
                "Payment not in APPROVED status for order " + orderId + " (current: " + payment.getStatus() + ")");
            return;
        }

        PaymentResult result = paymentProvider.refund(orderId, payment.getTransactionId(), payment.getAmount());

        if (result.isApproved()) {
            payment.setStatus(PaymentStatus.REFUNDED);
            Payment savedPayment = paymentRepository.saveAndFlush(payment);
            publishRefunded(savedPayment);
        } else {
            logger.warn("Provider declined refund for order {}: {}", orderId, result.getFailureReason());
            publishRefundFailed(orderId, result.getFailureReason());
        }
    }

    private void publishRefunded(Payment payment) {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId(payment.getOrderId());
        event.setTransactionId(payment.getTransactionId());

        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.PAYMENT_REFUNDED_KEY, event, CorrelationMessaging.withCorrelation());
        BusinessEventLog.info(logger, "payment.refunded.published", payment.getOrderId(), "PaymentEvent (refunded) published");
    }

    private void publishRefundFailed(String orderId, String reason) {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId(orderId);
        event.setFailureReason(reason);

        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.PAYMENT_REFUND_FAILED_KEY, event, CorrelationMessaging.withCorrelation());
        BusinessEventLog.info(logger, "payment.refund.failed.published", orderId, "PaymentEvent (refund failed) published: " + reason);
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