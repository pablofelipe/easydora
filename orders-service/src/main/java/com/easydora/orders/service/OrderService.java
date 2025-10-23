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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class OrderService {
    
    private final BuyerRepository buyerRepository;
    private final OrderRepository orderRepository;
    private final OrderStateMachineService stateMachineService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RabbitTemplate rabbitTemplate;
    
    private static final String ORDER_CREATED_TOPIC = "order-created";
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
        
        // Publicar evento de ordem criada
        publishOrderCreatedEvent(savedOrder);
        
        // Enviar comando para reservar estoque
        sendReserveStockCommand(savedOrder);
        
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
        if (order.getState() != OrderState.PENDING && order.getState() != OrderState.PAYMENT_APPROVED) {
            throw new RuntimeException("Cannot cancel order in state: " + order.getState());
        }
        
        // Enviar evento de cancelamento
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.CANCEL_ORDER);
        
        if (eventSent) {
            // Atualizar estado localmente
            order.setState(OrderState.CANCELLED);
            Order updatedOrder = orderRepository.save(order);
            
            // Publicar evento de mudança de estado
            publishOrderStatusChanged(orderId, order.getState(), OrderState.CANCELLED);
            
            return mapToOrderResponse(updatedOrder);
        } else {
            throw new RuntimeException("Failed to cancel order");
        }
    }
    
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
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.INVENTORY_RESERVED);
        
        if (eventSent) {
            OrderState newState = stateMachineService.getCurrentState(orderId);
            order.setState(newState);
            orderRepository.save(order);
            
            publishOrderStatusChanged(orderId, OrderState.PROCESSING, newState);
        }
    }
    
    public void handleInventoryFailed(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        
        boolean eventSent = stateMachineService.sendEvent(orderId, OrderEvent.INVENTORY_FAILED);
        
        if (eventSent) {
            OrderState newState = stateMachineService.getCurrentState(orderId);
            order.setState(newState);
            orderRepository.save(order);
            
            publishOrderStatusChanged(orderId, OrderState.PROCESSING, newState);
        }
    }
    
    private void publishOrderCreatedEvent(Order order) {
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId(order.getId());
        event.setUserId(order.getUserId());
        event.setTotalAmount(order.getTotalAmount());
        
        kafkaTemplate.send(ORDER_CREATED_TOPIC, order.getId(), event);
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
            command
        );
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
