package com.easydora.orders.service;

import com.easydora.orders.entity.Order;
import com.easydora.orders.exception.OrderNotFoundException;
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
    
    @Transactional
    public boolean sendEvent(String orderId, OrderEvent event) {
        logger.info("[STATE MACHINE] Iniciando processamento do evento {} para pedido {}", event, orderId);

        try {
            // 1. Buscar pedido
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    logger.error("Pedido não encontrado: {}", orderId);
                    return new OrderNotFoundException(orderId);
                });

            logger.info("Estado atual do pedido no banco: {}", order.getState());

            // 2. Criar State Machine com estado atual
            StateMachine<OrderState, OrderEvent> stateMachine = createStateMachineWithCurrentState(orderId, order.getState());

            if (stateMachine == null) {
                logger.error("Falha ao criar State Machine para pedido {}", orderId);
                return false;
            }

            // 3. Log do estado ANTES do evento
            logCurrentState(stateMachine, "ANTES do evento");

            // 4. Enviar evento
            boolean eventAccepted = stateMachine.sendEvent(event);
            logger.info("Evento {} enviado. Aceito? {}", event, eventAccepted);

            // 5. Log do estado DEPOIS do evento
            logCurrentState(stateMachine, "DEPOIS do evento");

            // 6. Se evento foi aceito, atualizar banco
            if (eventAccepted) {
                updateOrderStateFromStateMachine(order, stateMachine);
            } else {
                logger.warn("Evento {} não foi aceito pelo pedido {} no estado {}",
                    event, orderId, order.getState());
            }

            // 7. Parar State Machine para liberar recursos
            stateMachine.stop();

            return eventAccepted;

        } catch (Exception e) {
            logger.error("Erro crítico ao processar evento {} para pedido {}", event, orderId, e);
            return false;
        }
    }
    
    private StateMachine<OrderState, OrderEvent> createStateMachineWithCurrentState(
        String orderId, 
        OrderState currentState
    ) {
        try {
            logger.debug("Criando State Machine para pedido {} com estado {}", orderId, currentState);

            // Obter State Machine do factory
            StateMachine<OrderState, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(orderId);

            if (stateMachine == null) {
                logger.error("Factory retornou State Machine nula para pedido {}", orderId);
                return null;
            }
            
            // Parar se estiver rodando
            try {
                stateMachine.stop();
            } catch (Exception e) {
                // Ignorar erros ao parar
            }
            
            // Resetar para o estado atual
            stateMachine.getStateMachineAccessor()
                .doWithAllRegions(access -> {
                    access.resetStateMachine(new DefaultStateMachineContext<>(
                        currentState, 
                        null, 
                        null, 
                        null,
                        null,
                        stateMachine.getId()
                    ));
                });
            
            // Iniciar State Machine
            stateMachine.start();
            
            // Verificar se o estado foi configurado corretamente
            if (stateMachine.getState() == null) {
                logger.error("State Machine criada mas estado é NULL para pedido {}", orderId);
                // Tentar forçar o estado
                try {
                    Thread.sleep(50); // Pequena pausa
                    stateMachine.start(); // Tentar iniciar novamente
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            
            logger.debug("State Machine criada com sucesso para pedido {}", orderId);
            return stateMachine;

        } catch (Exception e) {
            logger.error("Erro ao criar State Machine para pedido {}", orderId, e);
            return null;
        }
    }
    
    private void logCurrentState(StateMachine<OrderState, OrderEvent> stateMachine, String momento) {
        try {
            if (stateMachine.getState() != null && stateMachine.getState().getId() != null) {
                logger.info("Estado {}: {}", momento, stateMachine.getState().getId());
            } else {
                logger.warn("Estado {}: NULL (State Machine pode não estar inicializada)", momento);
                logger.debug("Detalhes da State Machine - ID: {}, UUID: {}, Completa: {}", 
                    stateMachine.getId(), stateMachine.getUuid(), stateMachine.isComplete());
            }
        } catch (Exception e) {
            logger.error("Erro ao logar estado: {}", e.getMessage());
        }
    }
        
    private void updateOrderStateFromStateMachine(Order order, StateMachine<OrderState, OrderEvent> stateMachine) throws OrderNotFoundException {
        try {
            if (stateMachine.getState() != null && stateMachine.getState().getId() != null) {
                OrderState newState = stateMachine.getState().getId();
                
                order.setState(newState);
                Order orderFromDb = orderRepository.findById(order.getId())
                    .orElseThrow(() -> new OrderNotFoundException(order.getId()));
                
                orderFromDb.setState(newState);
                orderRepository.save(orderFromDb);
                
                logger.info("Estado salvo no banco: {}", newState);

            } else {
                logger.error("Não foi possível atualizar estado: State Machine retornou estado NULL");
            }
        } catch (Exception e) {
            logger.error("Erro ao atualizar estado no banco", e);
            throw e;
        }
    }
    
    // Método auxiliar para debug
    public void debugStateMachine(String orderId) {
        try {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
            
            logger.info("=== DEBUG STATE MACHINE ===");
            logger.info("Pedido ID: {}", orderId);
            logger.info("Estado no banco: {}", order.getState());
            
            StateMachine<OrderState, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(orderId);
            
            if (stateMachine == null) {
                logger.info("State Machine: NULL (factory retornou nulo)");
            } else {
                logger.info("State Machine ID: {}", stateMachine.getId());
                logger.info("State Machine UUID: {}", stateMachine.getUuid());
                logger.info("State Machine Completa: {}", stateMachine.isComplete());
                
                if (stateMachine.getState() != null) {
                    logger.info("Estado atual: {}", stateMachine.getState().getId());
                } else {
                    logger.info("Estado atual: NULL");
                }
                
                // Log todas as transições
                logger.info("Transições disponíveis:");
                stateMachine.getTransitions().forEach(t -> 
                    logger.info("  {} -> {} por {}", 
                        t.getSource() != null ? t.getSource().getId() : "null",
                        t.getTarget() != null ? t.getTarget().getId() : "null",
                        t.getTrigger() != null ? t.getTrigger().getEvent() : "null")
                );
            }
            logger.info("==========================");
            
        } catch (Exception e) {
            logger.error("Erro no debug", e);
        }
    }

    public StateMachine<OrderState, OrderEvent> createStateMachine(String orderId) {
        StateMachine<OrderState, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(orderId);
        stateMachine.start();
        
        // Restaurar estado do banco se existir
        orderRepository.findById(orderId).ifPresent(order -> {
            stateMachine.getStateMachineAccessor()
                .doWithAllRegions(access -> 
                    access.resetStateMachine(new DefaultStateMachineContext<>(
                        order.getState(), null, null, null
                    ))
                );
        });
        
        return stateMachine;
    }

    public OrderState getCurrentState(String orderId) {
        try {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
            return order.getState();
        } catch (Exception e) {
            logger.error("Erro ao obter estado atual do pedido {}", orderId, e);
            return null;
        }
    }
}