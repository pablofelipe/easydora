package com.easydora.orders.service;

import com.easydora.orders.exception.OrderNotFoundException;
import com.easydora.orders.entity.Order;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.statemachine.OrderEvent;
import com.easydora.orders.statemachine.OrderState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderStateMachineService {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderStateMachineService.class);
    
    private final StateMachineFactory<OrderState, OrderEvent> stateMachineFactory;
    private final OrderRepository orderRepository;

    public OrderStateMachineService(
        StateMachineFactory<OrderState, OrderEvent> stateMachineFactory,
        OrderRepository orderRepository
    ) {
        this.stateMachineFactory = stateMachineFactory;
        this.orderRepository = orderRepository;
    }

    public StateMachine<OrderState, OrderEvent> createStateMachine(String orderId) {
        StateMachine<OrderState, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(orderId);
        stateMachine.start();
        return stateMachine;
    }
    
    public StateMachine<OrderState, OrderEvent> getStateMachine(String orderId) {
        return stateMachineFactory.getStateMachine(orderId);
    }

    @Transactional
    public boolean sendEvent(String orderId, OrderEvent event) {
        logger.info("🤖 [STATE MACHINE] Tentando enviar evento {} para order {}", event, orderId);
        
        try {
            // 1. Buscar o pedido
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
            
            logger.info("📦 [STATE MACHINE] Estado atual no banco: {}", order.getState());
            
            // 2. Criar/obter a State Machine
            StateMachine<OrderState, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(orderId);
            
            // 3. Restaurar o estado do banco
            stateMachine.getStateMachineAccessor()
                .doWithAllRegions(access -> 
                    access.resetStateMachine(new DefaultStateMachineContext<>(
                        order.getState(), null, null, null
                    ))
                );
            
            // 4. Iniciar a State Machine
            stateMachine.start();
            
            // 5. Log do estado atual
            logger.info("🎯 [STATE MACHINE] Estado atual na máquina: {}", 
                stateMachine.getState() != null ? stateMachine.getState().getId() : "null");
            
            // 6. Enviar evento
            boolean accepted = stateMachine.sendEvent(event);
            logger.info("✅ [STATE MACHINE] Evento {} enviado? {}", event, accepted);
            
            // 7. Atualizar banco se a transição foi aceita
            if (accepted && stateMachine.getState() != null) {
                order.setState(stateMachine.getState().getId());
                orderRepository.save(order);
                logger.info("💾 [STATE MACHINE] Estado salvo no banco: {}", order.getState());
            } else if (!accepted) {
                logger.warn("⚠️ [STATE MACHINE] Evento {} não aceito no estado atual {}", 
                    event, order.getState());
            }
            
            return accepted;
            
        } catch (Exception e) {
            logger.error("💥 [STATE MACHINE] Erro ao processar evento {} para order {}", 
                event, orderId, e);
            throw new RuntimeException("Erro ao processar evento de state machine", e);
        }
    }
    
     public OrderState getCurrentState(String orderId) {
        StateMachine<OrderState, OrderEvent> stateMachine = getStateMachine(orderId);
        if (stateMachine != null) {
            return stateMachine.getState().getId();
        }
        return null;
    }

    // Método auxiliar para criar um novo pedido (se necessário)
    public StateMachine<OrderState, OrderEvent> createNewOrderStateMachine(String orderId, OrderState initialState) {
        StateMachine<OrderState, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(orderId);
        
        stateMachine.getStateMachineAccessor()
            .doWithAllRegions(access -> 
                access.resetStateMachine(new DefaultStateMachineContext<>(
                    initialState, null, null, null
                ))
            );
        
        stateMachine.start();
        return stateMachine;
    }
}