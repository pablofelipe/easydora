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
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class OrderService {
    
    private final BuyerRepository buyerRepository;
    private final OrderRepository orderRepository;
    private final OrderStateMachineService stateMachineService;
    @Autowired
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RabbitTemplate rabbitTemplate;
    
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private static final String ORDER_STATUS_CHANGED_TOPIC = "order-status-changed";

    public OrderService(BuyerRepository buyerRepository, 
                        OrderRepository orderRepository, 
                       OrderStateMachineService stateMachineService,
                       KafkaTemplate<String, Object> kafkaTemplate,
                       RabbitTemplate rabbitTemplate) {
        this.buyerRepository = buyerRepository;
        this.orderRepository = orderRepository;
        this.stateMachineService = stateMachineService;
        this.kafkaTemplate = kafkaTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }
    
    public OrderResponse createOrder(OrderRequest request, Long userId) {

        Buyer buyer = buyerRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("Buyer not found: " + userId));
        
        if (!buyer.isActive()) {
            throw new RuntimeException("Buyer account is not active");
        }
        // Criar ordem
        Order order = new Order();
        order.setId(UUID.randomUUID().toString());
        order.setUserId(userId);
        order.setState(OrderState.PENDING);
        
        // Adicionar items
        for (OrderItemRequest itemRequest : request.getItems()) {
            OrderItem item = new OrderItem();
            item.setId(UUID.randomUUID().toString());
            item.setProductId(itemRequest.getProductId());
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(itemRequest.getUnitPrice());
            order.addItem(item);
        }
        
        // Calcular total
        order.setTotalAmount(order.calculateTotal());
        
        // Salvar ordem
        Order savedOrder = orderRepository.save(order);

        // Iniciar state machine
        stateMachineService.createStateMachine(savedOrder.getId());
        
        boolean processingStarted = stateMachineService.sendEvent(
            savedOrder.getId(), 
            OrderEvent.START_PROCESSING);

        if (!processingStarted) {
            // Fallback: atualizar manualmente
            savedOrder.setState(OrderState.PROCESSING);
            orderRepository.save(savedOrder);
            logger.warn("⚠️ State machine não aceitou START_PROCESSING, usando fallback");
        } else {
            // Estado será atualizado pela state machine
            OrderState currentState = stateMachineService.getCurrentState(savedOrder.getId());
            savedOrder.setState(currentState);
            orderRepository.save(savedOrder);
        }

        orderRepository.flush();
        
        // Publicar evento de ordem criada
        publishOrderCreatedEvent(savedOrder);
        if (savedOrder.getState() == OrderState.PROCESSING) {
            sendReserveStockCommand(savedOrder);
        } else {
            logger.error("❌ Não foi possível iniciar reserva de estoque. Estado: {}", 
                savedOrder.getState());
        }
        

        return mapToOrderResponse(savedOrder);
    }
    
    public OrderResponse getOrder(String orderId, Long userId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        // Verificar se o buyer tem permissão para ver este pedido
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
        
        // Verificar se pode cancelar
        if (!canCancel(order)) {
            logger.error("❌ Pedido {} não pode ser cancelado no estado: {}", orderId, order.getState());
            throw new RuntimeException("Cannot cancel order in state: " + order.getState());
        }
        
        // Enviar evento de cancelamento
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.CANCEL_ORDER);
            
        if (eventSent) {
            // IMPORTANTE: Obter o estado atualizado da State Machine
            OrderState newState = stateMachineService.getCurrentState(orderId);
            logger.info("✅ Evento CANCEL_ORDER aceito. Novo estado: {}", newState);
            
            // Validar que realmente foi para CANCELLED
            if (newState != OrderState.CANCELLED) {
                logger.error("⚠️ Estado inesperado após cancelamento: {}", newState);
                throw new RuntimeException("Order not cancelled - unexpected state: " + newState);
            }
            
            // Atualizar estado no banco (apenas se a State Machine aceitou)
            OrderState previousState = order.getState();
            order.setState(newState);
            order.setUpdatedAt(Instant.now());
            Order updatedOrder = orderRepository.save(order);
            logger.info("💾 Pedido atualizado no banco: {} -> {}", previousState, newState);
            
            // Publicar evento de mudança de estado
            publishOrderStatusChanged(orderId, previousState, newState);
            
            // Se estava em INVENTORY_RESERVED, publicar evento específico
            if (previousState == OrderState.INVENTORY_RESERVED) {
                publishInventoryRelease(orderId);
            }
            
            return mapToOrderResponse(updatedOrder);
        } else {
            logger.error("❌ Evento CANCEL_ORDER não aceito pela State Machine");
            
            // Verificar qual é o estado atual
            OrderState currentState = stateMachineService.getCurrentState(orderId);
            logger.info("📌 Estado atual na State Machine: {}", currentState);
            
            throw new RuntimeException("Failed to cancel order - event not accepted. Current state: " + currentState);
        }
    }
        
    private void publishInventoryRelease(String orderId) {
        try {
            // Publicar evento para liberar estoque
            Map<String, Object> releaseEvent = new HashMap<>();
            releaseEvent.put("orderId", orderId);
            releaseEvent.put("eventType", "INVENTORY_RELEASE");
            releaseEvent.put("timestamp", LocalDateTime.now());
            
            rabbitTemplate.convertAndSend(
                "order.exchange",
                "inventory.release",
                releaseEvent
            );
            logger.info("📤 Evento de liberação de estoque publicado para pedido: {}", orderId);
        } catch (Exception e) {
            logger.error("❌ Erro ao publicar evento de liberação de estoque: {}", e.getMessage(), e);
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
        
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.PAYMENT_RECEIVED);
        
        if (eventSent) {
            OrderState newState = stateMachineService.getCurrentState(orderId);
            order.setState(newState);
            orderRepository.save(order);
            
            publishOrderStatusChanged(orderId, OrderState.PENDING, newState);
        }
    }
    
    @Transactional
    public void handlePaymentFailed(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.PAYMENT_FAILED);
        
        if (eventSent) {
            OrderState newState = stateMachineService.getCurrentState(orderId);
            order.setState(newState);
            orderRepository.save(order);
            
            publishOrderStatusChanged(orderId, OrderState.PENDING, newState);
        }
    }
    
    public void handleInventoryReserved(String orderId) {
        try {
            logger.info("🔄 [SERVICE] Processando inventory reserved para order: {}", orderId);
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
            logger.error("❌ [SERVICE] Erro em handleInventoryReserved para order {}: {}", orderId, e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    public void handleInventoryFailed(String orderId) {
        try {
            logger.info("🔄 [SERVICE] Processando inventory failed para order: {}", orderId);

            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
            
            boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.INVENTORY_FAILED);
            
            logger.info("🎯 [SERVICE] Evento INVENTORY_FAILED enviado? {}", eventSent);

            if (eventSent) {
                OrderState newState = stateMachineService.getCurrentState(orderId);
                order.setState(newState);
                orderRepository.save(order);
                
                logger.info("💾 [SERVICE] Order {} atualizada para estado: {}", orderId, newState);

                publishOrderStatusChanged(orderId, OrderState.PROCESSING, newState);
            }
        } catch (Exception e) {
            logger.error("❌ [SERVICE] Erro em handleInventoryFailed para order {}: {}", orderId, e.getMessage());
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
            
            // Publicar no Kafka
            kafkaTemplate.send("order.created.topic", event);
            logger.info("✅ OrderCreatedEvent publicado: {}", order.getId());
            
        } catch (Exception e) {
            logger.error("❌ Erro ao publicar OrderCreatedEvent: {}", e.getMessage(), e);
            // Não lançar exceção para não quebrar o fluxo principal
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
        
        kafkaTemplate.send(ORDER_STATUS_CHANGED_TOPIC, orderId, event);
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
            logger.info("✅ ReserveStockCommand enviado para order: {}", order.getId());
            
        } catch (Exception e) {
            logger.error("❌ Erro ao enviar ReserveStockCommand: {}", e.getMessage(), e);
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
