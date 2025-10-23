package com.easydora.orders.service;

import com.easydora.orders.statemachine.OrderEvent;
import com.easydora.orders.statemachine.OrderState;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;

@Service
public class OrderStateMachineService {
    
    private final StateMachineFactory<OrderState, OrderEvent> stateMachineFactory;
    
    public OrderStateMachineService(StateMachineFactory<OrderState, OrderEvent> stateMachineFactory) {
        this.stateMachineFactory = stateMachineFactory;
    }
    
    public StateMachine<OrderState, OrderEvent> createStateMachine(String orderId) {
        StateMachine<OrderState, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(orderId);
        stateMachine.start();
        return stateMachine;
    }
    
    public StateMachine<OrderState, OrderEvent> getStateMachine(String orderId) {
        return stateMachineFactory.getStateMachine(orderId);
    }
    
    public boolean sendEvent(String orderId, OrderEvent event) {
        StateMachine<OrderState, OrderEvent> stateMachine = getStateMachine(orderId);
        if (stateMachine != null) {
            return stateMachine.sendEvent(event);
        }
        return false;
    }
    
    public OrderState getCurrentState(String orderId) {
        StateMachine<OrderState, OrderEvent> stateMachine = getStateMachine(orderId);
        if (stateMachine != null) {
            return stateMachine.getState().getId();
        }
        return null;
    }
    
    // Método para restaurar estado de um pedido existente
    public void restoreStateMachine(String orderId, OrderState state) {
        StateMachine<OrderState, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(orderId);
        stateMachine.getStateMachineAccessor()
            .doWithAllRegions(access -> 
                access.resetStateMachine(new DefaultStateMachineContext<>(state, null, null, null))
            );
        stateMachine.start();
    }
}