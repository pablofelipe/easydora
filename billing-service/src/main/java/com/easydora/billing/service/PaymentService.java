package com.easydora.billing.service;

import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationContext;
import com.easydora.correlation.OutboxEnvelopeCodec;
import com.easydora.correlation.OutboxTraceparent;
import com.easydora.billing.dto.PaymentDTO;
import com.easydora.billing.entity.OutboxEvent;
import com.easydora.billing.exception.OrderNotReadyForPaymentException;
import com.easydora.billing.exception.PaymentNotFoundException;
import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.OutboxEventRepository;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.messaging.events.OrderCreatedEvent;
import com.easydora.billing.messaging.events.PaymentEvent;
import com.easydora.billing.service.provider.PaymentProvider;
import com.easydora.billing.service.provider.PaymentResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final PaymentProvider paymentProvider;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper outboxObjectMapper;
    private final Counter paymentsApprovedCounter;
    private final Counter paymentsFailedCounter;
    private final Tracer tracer;
    private final Propagator propagator;

    public PaymentService(PaymentRepository paymentRepository, PaymentProvider paymentProvider,
            OutboxEventRepository outboxEventRepository, ObjectMapper outboxObjectMapper,
            MeterRegistry meterRegistry, Tracer tracer, Propagator propagator) {
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.outboxEventRepository = outboxEventRepository;
        this.outboxObjectMapper = outboxObjectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
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

    // Reacts to orders-service's order.status-changed broadcast (see
    // OrderEventListener). A status-changed event can arrive before this
    // service's own Payment row exists yet (order.created and
    // order.status-changed race independently) -- a no-op, not an error,
    // since processPayment's guard rejects on a missing orderState anyway
    // and the row will simply pick up the next status-changed event.
    @Transactional
    public void updateOrderState(String orderId, String newState) {
        paymentRepository.findByOrderId(orderId).ifPresentOrElse(
            payment -> {
                payment.setOrderState(newState);
                paymentRepository.save(payment);
            },
            () -> logger.warn("order.status-changed received for order {} with no Payment yet -- ignoring", orderId)
        );
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

        // If already approved, return it -- checked before the state guard
        // below so a duplicate/replayed call for a payment that already
        // succeeded stays a no-op even if the order has since moved past
        // INVENTORY_RESERVED (e.g. into PAYMENT_APPROVED, its own next
        // state).
        if (payment.getStatus() == PaymentStatus.APPROVED) {
            logger.warn("Payment already APPROVED for order {}", orderId);
            return convertToDTO(payment);
        }

        // ADR-0026 documented this endpoint as callable "at any time" by
        // any direct caller, relying entirely on ADR-0034's compensation
        // saga to unwind a payment approved too early or too late. This
        // guard closes the avoidable case at the source: a real customer
        // action (or gateway callback) should only ever land here once
        // the order has actually reached INVENTORY_RESERVED. The saga
        // still exists for genuine races this guard can't see (e.g. the
        // order moves on between this check and the provider call below).
        if (!"INVENTORY_RESERVED".equals(payment.getOrderState())) {
            logger.warn("Order {} is not ready for payment (state: {})", orderId, payment.getOrderState());
            throw new OrderNotReadyForPaymentException(orderId, payment.getOrderState());
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

        writeOutboxEvent(RabbitMQConfig.ORDER_EXCHANGE, routingKey, event);
        BusinessEventLog.info(logger, routingKey + ".outboxed", payment.getOrderId(), "PaymentEvent recorded in outbox");

        if (payment.getStatus() == PaymentStatus.APPROVED) {
            paymentsApprovedCounter.increment();
        } else {
            paymentsFailedCounter.increment();
        }
    }

    // Single write path for every outbox-backed publish in this service
    // (ADR-0037): serializes the event with the same ObjectMapper the
    // RabbitMQ message converter uses, so the stored text is byte-for-byte
    // what a direct convertAndSend would have put on the wire, then wraps
    // it with the correlationId/messageId envelope OutboxPublisher later
    // unwraps and promotes to native AMQP properties. Runs as a plain
    // repository save inside this method's own @Transactional scope --
    // deliberately no try/catch: a failure here is a DB failure, and must
    // roll back the same domain change that produced this event, exactly
    // like paymentRepository.save/saveAndFlush already does.
    private void writeOutboxEvent(String exchange, String routingKey, Object payload) {
        try {
            String body = outboxObjectMapper.writeValueAsString(payload);
            String envelopedPayload = OutboxEnvelopeCodec.wrap(
                    CorrelationContext.currentOrNewCorrelationId(),
                    CorrelationContext.newMessageId(),
                    OutboxTraceparent.capture(tracer, propagator),
                    body);
            outboxEventRepository.save(new OutboxEvent(exchange, routingKey, envelopedPayload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for routing key " + routingKey, e);
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

        writeOutboxEvent(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.PAYMENT_REFUNDED_KEY, event);
        BusinessEventLog.info(logger, "payment.refunded.outboxed", payment.getOrderId(), "PaymentEvent (refunded) recorded in outbox");
    }

    private void publishRefundFailed(String orderId, String reason) {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId(orderId);
        event.setFailureReason(reason);

        writeOutboxEvent(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.PAYMENT_REFUND_FAILED_KEY, event);
        BusinessEventLog.info(logger, "payment.refund.failed.outboxed", orderId, "PaymentEvent (refund failed) recorded in outbox: " + reason);
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