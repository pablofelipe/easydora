package com.easydora.orders.service;

import com.easydora.orders.dto.OrderRequest;
import com.easydora.orders.dto.OrderResponse;
import com.easydora.orders.dto.OrderItemRequest;
import com.easydora.orders.dto.OrderItemResponse;
import com.easydora.orders.entity.Buyer;
import com.easydora.orders.entity.Order;
import com.easydora.orders.entity.OrderItem;
import com.easydora.orders.event.OrderCreatedEvent;
import com.easydora.orders.event.ReserveStockCommand;
import com.easydora.orders.repository.BuyerRepository;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.statemachine.OrderEvent;
import com.easydora.orders.statemachine.OrderState;
import com.easydora.orders.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    private final RabbitTemplate rabbitTemplate;

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    public OrderService(BuyerRepository buyerRepository,
                        OrderRepository orderRepository,
                       OrderStateMachineService stateMachineService,
                       RabbitTemplate rabbitTemplate) {
        this.buyerRepository = buyerRepository;
        this.orderRepository = orderRepository;
        this.stateMachineService = stateMachineService;
        this.rabbitTemplate = rabbitTemplate;
    }
    
    public OrderResponse createOrder(OrderRequest request, Long userId) {

        Buyer buyer = buyerRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("Buyer not found: " + userId));
        
        if (!buyer.isActive()) {
            throw new RuntimeException("Buyer account is not active");
        }
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

            // Update state in the database (only if the State Machine accepted it)
            OrderState previousState = order.getState();
            order.setState(newState);
            order.setUpdatedAt(Instant.now());
            Order updatedOrder = orderRepository.save(order);
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
        return order.getState() == OrderState.PENDING || 
            order.getState() == OrderState.PAYMENT_APPROVED ||
            order.getState() == OrderState.PROCESSING ||
            order.getState() == OrderState.INVENTORY_RESERVED;
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
            orderRepository.save(order);

            publishOrderStatusChanged(orderId, previousState, newState);
        }
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
            orderRepository.save(order);

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
                orderRepository.save(order);
                
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
                orderRepository.save(order);
                
                logger.info("[SERVICE] Order {} updated to state: {}", orderId, newState);

                publishOrderStatusChanged(orderId, OrderState.PROCESSING, newState);
            }
        } catch (Exception e) {
            logger.error("[SERVICE] Error in handleInventoryFailed for order {}: {}", orderId, e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    private void publishOrderCreatedEvent(Order order) {
        try {
            OrderCreatedEvent event = new OrderCreatedEvent();
            event.setOrderId(order.getId());
            event.setUserId(order.getUserId());
            event.setTotalAmount(order.getTotalAmount());
            event.setItems(order.getItems().stream()
                .map(this::mapToEventItem)
                .collect(Collectors.toList()));
            event.setCreatedAt(order.getCreatedAt());
            
            rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.ORDER_CREATED_KEY, event);
            logger.info("OrderCreatedEvent published: {}", order.getId());

        } catch (Exception e) {
            logger.error("Error publishing OrderCreatedEvent: {}", e.getMessage(), e);
            // Do not throw, so the main flow isn't broken
        }
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
        
        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.ORDER_STATUS_CHANGED_KEY, event);
    }
    
    private void sendReserveStockCommand(Order order) {
        try
        {

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
            
            rabbitTemplate.convertAndSend(
                "order.exchange",
                "stock.reserve",
                command,
                message -> {
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setPriority(0);
                    return message;
                }
            );
            logger.info("ReserveStockCommand sent for order: {}", order.getId());

        } catch (Exception e) {
            logger.error("Error sending ReserveStockCommand: {}", e.getMessage(), e);
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
