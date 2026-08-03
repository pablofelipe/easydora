package com.easydora.orders.service;

import com.easydora.orders.dto.OrderRequest;
import com.easydora.orders.dto.OrderResponse;
import com.easydora.orders.dto.OrderItemRequest;
import com.easydora.orders.dto.OrderItemResponse;
import com.easydora.orders.entity.Buyer;
import com.easydora.orders.entity.Order;
import com.easydora.orders.entity.OrderItem;
import com.easydora.orders.entity.OutboxEvent;
import com.easydora.orders.event.OrderCreatedEvent;
import com.easydora.orders.event.RefundPaymentCommand;
import com.easydora.orders.event.ReserveStockCommand;
import com.easydora.orders.repository.BuyerRepository;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.repository.OutboxEventRepository;
import com.easydora.orders.repository.ProductOwnershipRepository;
import com.easydora.orders.statemachine.OrderEvent;
import com.easydora.orders.statemachine.OrderState;
import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationContext;
import com.easydora.correlation.OutboxEnvelopeCodec;
import com.easydora.correlation.OutboxTraceparent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class OrderService {
    
    private final BuyerRepository buyerRepository;
    private final OrderRepository orderRepository;
    private final OrderStateMachineService stateMachineService;
    private final ProductOwnershipRepository productOwnershipRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper outboxObjectMapper;
    private final Counter ordersCreatedCounter;
    private final Tracer tracer;
    private final Propagator propagator;

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    public OrderService(BuyerRepository buyerRepository,
                        OrderRepository orderRepository,
                       OrderStateMachineService stateMachineService,
                       ProductOwnershipRepository productOwnershipRepository,
                       OutboxEventRepository outboxEventRepository,
                       ObjectMapper outboxObjectMapper,
                       MeterRegistry meterRegistry,
                       Tracer tracer,
                       Propagator propagator) {
        this.buyerRepository = buyerRepository;
        this.orderRepository = orderRepository;
        this.stateMachineService = stateMachineService;
        this.productOwnershipRepository = productOwnershipRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.outboxObjectMapper = outboxObjectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
        // Business metric (ADR-0036): infra-level metrics (request rate,
        // JVM, RabbitMQ) already answer "is the system healthy"; this one
        // answers a question infra can't -- "how much business is actually
        // flowing through it".
        this.ordersCreatedCounter = meterRegistry.counter("orders_created_total");
    }

    public OrderResponse createOrder(OrderRequest request, Long userId) {

        Buyer buyer = buyerRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("Buyer not found: " + userId));

        if (!buyer.isActive()) {
            throw new RuntimeException("Buyer account is not active");
        }

        rejectSelfPurchase(request, userId);

        // Create order
        Order order = new Order();
        order.setId(UUID.randomUUID().toString());
        order.setUserId(userId);
        order.setState(OrderState.PENDING);

        // Add items
        for (OrderItemRequest itemRequest : request.getItems()) {
            OrderItem item = new OrderItem();
            item.setId(UUID.randomUUID().toString());
            item.setProductId(itemRequest.getProductId());
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(itemRequest.getUnitPrice());
            order.addItem(item);
        }
        
        // Calculate total
        order.setTotalAmount(order.calculateTotal());

        // Save order
        Order savedOrder = orderRepository.save(order);

        // Start state machine
        stateMachineService.createStateMachine(savedOrder.getId());
        
        boolean processingStarted = stateMachineService.sendEvent(
            savedOrder.getId(), 
            OrderEvent.START_PROCESSING);

        if (!processingStarted) {
            // Fallback: update manually
            savedOrder.setState(OrderState.PROCESSING);
            orderRepository.save(savedOrder);
            logger.warn("State machine did not accept START_PROCESSING, using fallback");
        } else {
            // State will be updated by the state machine
            OrderState currentState = stateMachineService.getCurrentState(savedOrder.getId());
            savedOrder.setState(currentState);
            orderRepository.save(savedOrder);
        }

        orderRepository.flush();
        
        // Publish order-created event
        publishOrderCreatedEvent(savedOrder);
        if (savedOrder.getState() == OrderState.PROCESSING) {
            sendReserveStockCommand(savedOrder);
        } else {
            logger.error("Could not start stock reservation. State: {}",
                savedOrder.getState());
        }
        

        return mapToOrderResponse(savedOrder);
    }
    
    public OrderResponse getOrder(String orderId, Long userId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        // Check whether the buyer has permission to view this order
        buyerRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("Buyer not found: " + userId));

        return mapToOrderResponse(order);
    }
    
    public List<OrderResponse> getUserOrders(Long userId) {
        buyerRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("Buyer not found: " + userId));

        List<Order> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return orders.stream()
                .map(this::mapToOrderResponse)
                .collect(Collectors.toList());
    }
    
    public OrderResponse cancelOrder(String orderId, Long userId) {
        buyerRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("Buyer not found: " + userId));
            
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        
        // Check whether it can be cancelled
        if (!canCancel(order)) {
            logger.error("Order {} cannot be cancelled in state: {}", orderId, order.getState());
            throw new RuntimeException("Cannot cancel order in state: " + order.getState());
        }

        // Captured before sendEvent: OrderStateMachineService.sendEvent
        // mutates this same Hibernate-managed Order instance in place
        // (findById inside the same transaction returns the identical
        // object), so reading order.getState() afterward would already
        // show the new state, not the real previous one.
        OrderState previousState = order.getState();

        // Send cancellation event
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.CANCEL_ORDER);

        if (eventSent) {
            // IMPORTANT: Get the updated state from the State Machine
            OrderState newState = stateMachineService.getCurrentState(orderId);
            logger.info("CANCEL_ORDER event accepted. New state: {}", newState);

            // Validate that it really moved to CANCELLED
            if (newState != OrderState.CANCELLED) {
                logger.error("Unexpected state after cancellation: {}", newState);
                throw new RuntimeException("Order not cancelled - unexpected state: " + newState);
            }

            // Update state in the database (only if the State Machine accepted it).
            // saveAndFlush (ADR-0033): forces the version check to happen here,
            // before publishOrderStatusChanged -- a conflict must never be
            // discovered after the event has already gone out.
            order.setState(newState);
            order.setUpdatedAt(Instant.now());
            Order updatedOrder = orderRepository.saveAndFlush(order);
            logger.info("Order updated in database: {} -> {}", previousState, newState);

            // Publish state-change event
            publishOrderStatusChanged(orderId, previousState, newState);
            // Note: inventory release for the INVENTORY_RESERVED case is
            // handled by ReleaseInventoryAction, triggered by the state
            // machine itself on this same CANCEL_ORDER transition — not
            // duplicated here.

            return mapToOrderResponse(updatedOrder);
        } else {
            logger.error("CANCEL_ORDER event not accepted by the State Machine");

            // Check what the current state is
            OrderState currentState = stateMachineService.getCurrentState(orderId);
            logger.info("Current state in the State Machine: {}", currentState);
            
            throw new RuntimeException("Failed to cancel order - event not accepted. Current state: " + currentState);
        }
    }

    private boolean canCancel(Order order) {
        return stateMachineService.isTransitionAllowed(order.getState(), OrderEvent.CANCEL_ORDER);
    }

    // Platform-operations action, not seller- or buyer-scoped: OrderItem
    // has no sellerId, so "the seller of this order" isn't a well-defined
    // question once an order spans more than one seller's products.
    // Authorization (ADMIN role) is enforced in the controller.
    public OrderResponse shipOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if (!stateMachineService.isTransitionAllowed(order.getState(), OrderEvent.SHIP_ORDER)) {
            logger.error("Order {} cannot be shipped in state: {}", orderId, order.getState());
            throw new RuntimeException("Cannot ship order in state: " + order.getState());
        }

        // Captured before sendEvent -- see cancelOrder's comment above for why.
        OrderState previousState = order.getState();
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.SHIP_ORDER);

        if (eventSent) {
            OrderState newState = stateMachineService.getCurrentState(orderId);
            logger.info("SHIP_ORDER event accepted. New state: {}", newState);

            if (newState != OrderState.SHIPPED) {
                logger.error("Unexpected state after shipping: {}", newState);
                throw new RuntimeException("Order not shipped - unexpected state: " + newState);
            }

            // saveAndFlush (ADR-0033): see cancelOrder's comment above.
            order.setState(newState);
            order.setUpdatedAt(Instant.now());
            Order updatedOrder = orderRepository.saveAndFlush(order);

            publishOrderStatusChanged(orderId, previousState, newState);

            return mapToOrderResponse(updatedOrder);
        } else {
            logger.error("SHIP_ORDER event not accepted by the State Machine");
            OrderState currentState = stateMachineService.getCurrentState(orderId);
            throw new RuntimeException("Failed to ship order - event not accepted. Current state: " + currentState);
        }
    }

    // Buyer-owned, mirroring cancelOrder's ownership pattern exactly:
    // only the order's own buyer can confirm delivery of what only they
    // can know they received.
    public OrderResponse deliverOrder(String orderId, Long userId) {
        buyerRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("Buyer not found: " + userId));

        Order order = orderRepository.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if (!stateMachineService.isTransitionAllowed(order.getState(), OrderEvent.DELIVER_ORDER)) {
            logger.error("Order {} cannot be delivered in state: {}", orderId, order.getState());
            throw new RuntimeException("Cannot deliver order in state: " + order.getState());
        }

        // Captured before sendEvent -- see cancelOrder's comment above for why.
        OrderState previousState = order.getState();
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.DELIVER_ORDER);

        if (eventSent) {
            OrderState newState = stateMachineService.getCurrentState(orderId);
            logger.info("DELIVER_ORDER event accepted. New state: {}", newState);

            if (newState != OrderState.DELIVERED) {
                logger.error("Unexpected state after delivery: {}", newState);
                throw new RuntimeException("Order not delivered - unexpected state: " + newState);
            }

            // saveAndFlush (ADR-0033): see cancelOrder's comment above.
            order.setState(newState);
            order.setUpdatedAt(Instant.now());
            Order updatedOrder = orderRepository.saveAndFlush(order);

            publishOrderStatusChanged(orderId, previousState, newState);

            return mapToOrderResponse(updatedOrder);
        } else {
            logger.error("DELIVER_ORDER event not accepted by the State Machine");
            OrderState currentState = stateMachineService.getCurrentState(orderId);
            throw new RuntimeException("Failed to deliver order - event not accepted. Current state: " + currentState);
        }
    }

    // Platform-operations read model: which paid orders are waiting to be
    // shipped. Reuses OrderRepository.findByState -- no join, no new
    // table, since ship is not seller-scoped (see shipOrder above).
    public List<OrderResponse> getFulfillmentQueue() {
        return orderRepository.findByState(OrderState.PAYMENT_APPROVED).stream()
            .map(this::mapToOrderResponse)
            .collect(Collectors.toList());
    }
    
    @Transactional
    public void handlePaymentReceived(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        OrderState previousState = order.getState();
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.PAYMENT_RECEIVED);

        if (eventSent) {
            OrderState newState = stateMachineService.getCurrentState(orderId);
            order.setState(newState);
            // saveAndFlush (ADR-0033): see OrderService.cancelOrder's comment.
            orderRepository.saveAndFlush(order);

            publishOrderStatusChanged(orderId, previousState, newState);
            return;
        }

        // PAYMENT_RECEIVED was not accepted from the order's current state.
        // ADR-0034: if that's because the order already reached
        // INVENTORY_FAILED/CANCELLED, Billing already approved a charge for
        // an order that can no longer be honored -- this is the one place
        // Orders detects that, and the one that initiates compensation.
        // Any other rejection (e.g. a duplicate/redelivered payment.approved
        // for an order already REFUNDING/REFUNDED/REFUND_FAILED) is a
        // legitimate no-op, guarded by the state machine itself: only
        // INVENTORY_FAILED/CANCELLED have an INITIATE_REFUND transition.
        if (previousState == OrderState.INVENTORY_FAILED || previousState == OrderState.CANCELLED) {
            initiateRefundCompensation(order, previousState);
        } else {
            logger.warn("PAYMENT_RECEIVED not accepted for order {} in state {} -- no compensation triggered",
                orderId, previousState);
        }
    }

    private void initiateRefundCompensation(Order order, OrderState previousState) {
        String orderId = order.getId();
        boolean refundInitiated = stateMachineService.sendEvent(orderId, OrderEvent.INITIATE_REFUND);

        if (!refundInitiated) {
            logger.error("INITIATE_REFUND not accepted for order {} in state {} -- unexpected", orderId, previousState);
            return;
        }

        OrderState newState = stateMachineService.getCurrentState(orderId);
        order.setState(newState);
        // saveAndFlush (ADR-0033): see OrderService.cancelOrder's comment.
        orderRepository.saveAndFlush(order);

        publishOrderStatusChanged(orderId, previousState, newState);
        publishRefundPaymentCommand(orderId);
    }

    private void publishRefundPaymentCommand(String orderId) {
        RefundPaymentCommand command = new RefundPaymentCommand();
        command.setOrderId(orderId);

        writeOutboxEvent(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.REFUND_PAYMENT_REQUESTED_KEY, command);
        BusinessEventLog.info(logger, "payment.refund.requested.outboxed", orderId, "RefundPaymentCommand recorded in outbox");
    }

    @Transactional
    public void handlePaymentFailed(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        OrderState previousState = order.getState();
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.PAYMENT_FAILED);

        if (eventSent) {
            OrderState newState = stateMachineService.getCurrentState(orderId);
            order.setState(newState);
            // saveAndFlush (ADR-0033): see OrderService.cancelOrder's comment.
            orderRepository.saveAndFlush(order);

            publishOrderStatusChanged(orderId, previousState, newState);
        }
    }
    
    public void handleInventoryReserved(String orderId) {
        try {
            logger.info("[SERVICE] Processing inventory reserved for order: {}", orderId);
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
            
            boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.INVENTORY_RESERVED);
            
            if (eventSent) {
                OrderState newState = stateMachineService.getCurrentState(orderId);
                order.setState(newState);
                // saveAndFlush (ADR-0033): see OrderService.cancelOrder's comment.
                orderRepository.saveAndFlush(order);

                publishOrderStatusChanged(orderId, OrderState.PROCESSING, newState);
            }
        } catch (Exception e) {
            logger.error("[SERVICE] Error in handleInventoryReserved for order {}: {}", orderId, e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    public void handleInventoryFailed(String orderId) {
        try {
            logger.info("[SERVICE] Processing inventory failed for order: {}", orderId);

            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
            
            boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.INVENTORY_FAILED);
            
            logger.info("[SERVICE] INVENTORY_FAILED event sent? {}", eventSent);

            if (eventSent) {
                OrderState newState = stateMachineService.getCurrentState(orderId);
                order.setState(newState);
                // saveAndFlush (ADR-0033): see OrderService.cancelOrder's comment.
                orderRepository.saveAndFlush(order);

                logger.info("[SERVICE] Order {} updated to state: {}", orderId, newState);

                publishOrderStatusChanged(orderId, OrderState.PROCESSING, newState);
            }
        } catch (Exception e) {
            logger.error("[SERVICE] Error in handleInventoryFailed for order {}: {}", orderId, e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    // Fraud-prevention rule: a SELLER may buy anything except their own
    // product; a BUYER is unaffected either way. Runs before any write
    // (order row, state machine, published event) so a rejection has zero
    // side effects -- no reservation, no payment, nothing to unwind.
    //
    // HTTP status: this throws a plain RuntimeException, which
    // GlobalExceptionHandler maps to 400 Bad Request -- the same mapping
    // every other business-rule rejection in this class already uses
    // ("Buyer not found", "Buyer account is not active", "Cannot cancel
    // order in state X"). 403 Forbidden is arguably a more precise HTTP
    // semantic for "authenticated, but not allowed to act on this
    // resource", but this service has no existing precedent for
    // status-per-rule-type differentiation; introducing one just for this
    // rule would break that consistency for no real benefit here.
    // ADR-0034: the other end of the compensation round-trip -- Billing
    // resolved the RefundPaymentCommand and published the outcome. Same
    // saveAndFlush-then-publish pattern as every other outcome handler in
    // this class; a redelivered/duplicate outcome for an order that already
    // left REFUNDING is a no-op, guarded by the state machine itself (only
    // REFUNDING has these two transitions).
    public void handleRefundCompleted(String orderId) {
        try {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            OrderState previousState = order.getState();
            boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.REFUND_COMPLETED);

            if (eventSent) {
                OrderState newState = stateMachineService.getCurrentState(orderId);
                order.setState(newState);
                orderRepository.saveAndFlush(order);
                publishOrderStatusChanged(orderId, previousState, newState);
            } else {
                logger.warn("REFUND_COMPLETED not accepted for order {} in state {}", orderId, previousState);
            }
        } catch (Exception e) {
            logger.error("Error in handleRefundCompleted for order {}: {}", orderId, e.getMessage());
            throw e;
        }
    }

    // ADR-0034: payment.refund.failed signals either a genuine business
    // decline from the provider or a precondition Billing found unmet
    // (Payment not found / not APPROVED) -- both currently require the same
    // action from Orders (a terminal, human-reviewable state; see the ADR
    // for why they're not split into two different Order states). No
    // automatic retry here: ADR-0019's transport-level retry already
    // covers transient delivery failures, and this failure mode isn't
    // transient. reason is persisted (not just logged) so the admin
    // remediation queue (getRefundFailedQueue) can show it without
    // cross-referencing logs.
    public void handleRefundFailed(String orderId, String reason) {
        try {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            OrderState previousState = order.getState();
            boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.REFUND_FAILED);

            if (eventSent) {
                OrderState newState = stateMachineService.getCurrentState(orderId);
                order.setState(newState);
                order.setRefundFailureReason(reason);
                orderRepository.saveAndFlush(order);
                logger.error("Refund failed for order {}: {}", orderId, reason);
                publishOrderStatusChanged(orderId, previousState, newState);
            } else {
                logger.warn("REFUND_FAILED not accepted for order {} in state {}", orderId, previousState);
            }
        } catch (Exception e) {
            logger.error("Error in handleRefundFailed for order {}: {}", orderId, e.getMessage());
            throw e;
        }
    }

    // Platform-operations read model: orders stuck in the REFUND_FAILED
    // dead end, needing manual review (ADR-0034's own documented residual
    // gap). Same role gate as getFulfillmentQueue/shipOrder.
    public List<OrderResponse> getRefundFailedQueue() {
        return orderRepository.findByState(OrderState.REFUND_FAILED).stream()
            .map(this::mapToOrderResponse)
            .collect(Collectors.toList());
    }

    // Operator-initiated remediation: sends a REFUND_FAILED order back
    // through REFUNDING and re-publishes RefundPaymentCommand, exactly
    // the same command a fresh INITIATE_REFUND would have produced.
    // billing-service's refundPayment is already idempotent (a redelivered
    // command for an already-REFUNDED Payment is a no-op), so retrying
    // against a Payment that turned out fine is harmless; retrying against
    // the same still-broken precondition (Payment missing/not APPROVED)
    // simply produces the same payment.refund.failed again, which is the
    // correct, honest outcome, not something to hide.
    @Transactional
    public OrderResponse retryRefund(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        OrderState previousState = order.getState();
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.RETRY_REFUND);

        if (!eventSent) {
            throw new RuntimeException("Cannot retry refund for order " + orderId + " in state " + previousState);
        }

        OrderState newState = stateMachineService.getCurrentState(orderId);
        order.setState(newState);
        order.setRefundFailureReason(null);
        orderRepository.saveAndFlush(order);

        publishOrderStatusChanged(orderId, previousState, newState);
        publishRefundPaymentCommand(orderId);

        return mapToOrderResponse(order);
    }

    private void rejectSelfPurchase(OrderRequest request, Long userId) {
        String buyerId = String.valueOf(userId);
        for (OrderItemRequest item : request.getItems()) {
            productOwnershipRepository.findById(item.getProductId())
                .filter(ownership -> buyerId.equals(ownership.getSellerId()))
                .ifPresent(ownership -> {
                    throw new RuntimeException("Cannot purchase your own product");
                });
        }
    }

    private void publishOrderCreatedEvent(Order order) {
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId(order.getId());
        event.setUserId(order.getUserId());
        event.setTotalAmount(order.getTotalAmount());
        event.setItems(order.getItems().stream()
            .map(this::mapToEventItem)
            .collect(Collectors.toList()));
        event.setCreatedAt(order.getCreatedAt());

        writeOutboxEvent(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.ORDER_CREATED_KEY, event);
        BusinessEventLog.info(logger, "order.created.outboxed", order.getId(), "OrderCreatedEvent recorded in outbox");
        ordersCreatedCounter.increment();
    }
    
    private com.easydora.orders.event.OrderCreatedEvent.OrderItem mapToEventItem(OrderItem item) {
        com.easydora.orders.event.OrderCreatedEvent.OrderItem eventItem = 
            new com.easydora.orders.event.OrderCreatedEvent.OrderItem();
        eventItem.setProductId(item.getProductId());
        eventItem.setQuantity(item.getQuantity());
        eventItem.setUnitPrice(item.getUnitPrice());
        return eventItem;
    }
    
    private void publishOrderStatusChanged(String orderId, OrderState previousState, OrderState newState) {
        com.easydora.orders.event.OrderStatusChangedEvent event =
            new com.easydora.orders.event.OrderStatusChangedEvent();
        event.setOrderId(orderId);
        event.setPreviousState(previousState);
        event.setNewState(newState);

        writeOutboxEvent(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.ORDER_STATUS_CHANGED_KEY, event);
        BusinessEventLog.info(logger, "order.status-changed.outboxed", orderId,
                "OrderStatusChangedEvent recorded in outbox: " + previousState + " -> " + newState);
    }

    private void sendReserveStockCommand(Order order) {
        ReserveStockCommand command = new ReserveStockCommand();
        command.setOrderId(order.getId());

        List<ReserveStockCommand.OrderItemDTO> items = order.getItems().stream()
        .map(item -> {
                ReserveStockCommand.OrderItemDTO dto = new ReserveStockCommand.OrderItemDTO();
                dto.setProductId(item.getProductId());
                dto.setQuantity(item.getQuantity());
                return dto;
            })
            .collect(Collectors.toList());

        command.setItems(items);

        writeOutboxEvent(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.RESERVE_STOCK_ROUTING_KEY, command);
        BusinessEventLog.info(logger, "stock.reserve.outboxed", order.getId(), "ReserveStockCommand recorded in outbox");
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
    // like orderRepository.save/saveAndFlush already does.
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

    private OrderResponse mapToOrderResponse(Order order) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setUserId(order.getUserId());
        response.setTotalAmount(order.getTotalAmount());
        response.setState(order.getState());
        response.setCreatedAt(order.getCreatedAt());
        response.setUpdatedAt(order.getUpdatedAt());
        response.setRefundFailureReason(order.getRefundFailureReason());

        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(this::mapToOrderItemResponse)
                .collect(Collectors.toList());
        response.setItems(itemResponses);
        
        return response;
    }
    
    private OrderItemResponse mapToOrderItemResponse(OrderItem item) {
        OrderItemResponse response = new OrderItemResponse();
        response.setId(item.getId());
        response.setProductId(item.getProductId());
        response.setQuantity(item.getQuantity());
        response.setUnitPrice(item.getUnitPrice());
        response.setSubtotal(item.getSubtotal());
        return response;
    }
}
