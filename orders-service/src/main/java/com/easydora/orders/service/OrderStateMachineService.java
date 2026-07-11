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

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OrderStateMachineService {

    private static final Logger logger = LoggerFactory.getLogger(OrderStateMachineService.class);

    private final StateMachineFactory<OrderState, OrderEvent> stateMachineFactory;
    private final OrderRepository orderRepository;

    // Lazily computed, then cached: the transition graph never changes at
    // runtime, and this is the single place the whole service consults to
    // answer "is event E legal from state S" -- built directly from the
    // real OrderStateMachineConfig instead of a hand-written duplicate of
    // it, which is exactly what let canCancel() drift from the actual
    // configured transitions before this refactor.
    private volatile Map<OrderState, Set<OrderEvent>> allowedTransitionsCache;

    public OrderStateMachineService(
        StateMachineFactory<OrderState, OrderEvent> stateMachineFactory,
        OrderRepository orderRepository
    ) {
        this.stateMachineFactory = stateMachineFactory;
        this.orderRepository = orderRepository;
    }

    public boolean isTransitionAllowed(OrderState from, OrderEvent event) {
        return allowedTransitions().getOrDefault(from, Set.of()).contains(event);
    }

    private Map<OrderState, Set<OrderEvent>> allowedTransitions() {
        Map<OrderState, Set<OrderEvent>> cache = allowedTransitionsCache;
        if (cache == null) {
            StateMachine<OrderState, OrderEvent> probe = stateMachineFactory.getStateMachine();
            cache = probe.getTransitions().stream()
                .collect(Collectors.groupingBy(
                    t -> t.getSource().getId(),
                    Collectors.mapping(t -> t.getTrigger().getEvent(), Collectors.toSet())
                ));
            allowedTransitionsCache = cache;
        }
        return cache;
    }
    
    @Transactional
    public boolean sendEvent(String orderId, OrderEvent event) {
        logger.info("[STATE MACHINE] Starting to process event {} for order {}", event, orderId);

        try {
            // 1. Look up the order
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    logger.error("Order not found: {}", orderId);
                    return new OrderNotFoundException(orderId);
                });

            logger.info("Current order state in the database: {}", order.getState());

            // 2. Create State Machine with the current state
            StateMachine<OrderState, OrderEvent> stateMachine = createStateMachineWithCurrentState(orderId, order.getState());

            if (stateMachine == null) {
                logger.error("Failed to create State Machine for order {}", orderId);
                return false;
            }

            // 3. Log the state BEFORE the event
            logCurrentState(stateMachine, "BEFORE the event");

            // 4. Send the event
            boolean eventAccepted = stateMachine.sendEvent(event);
            logger.info("Event {} sent. Accepted? {}", event, eventAccepted);

            // 5. Log the state AFTER the event
            logCurrentState(stateMachine, "AFTER the event");

            // 6. If the event was accepted, update the database
            if (eventAccepted) {
                updateOrderStateFromStateMachine(order, stateMachine);
            } else {
                logger.warn("Event {} was not accepted for order {} in state {}",
                    event, orderId, order.getState());
            }

            // 7. Stop the State Machine to free resources
            stateMachine.stop();

            return eventAccepted;

        } catch (Exception e) {
            logger.error("Critical error processing event {} for order {}", event, orderId, e);
            return false;
        }
    }
    
    private StateMachine<OrderState, OrderEvent> createStateMachineWithCurrentState(
        String orderId, 
        OrderState currentState
    ) {
        try {
            logger.debug("Creating State Machine for order {} with state {}", orderId, currentState);

            // Get State Machine from the factory
            StateMachine<OrderState, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(orderId);

            if (stateMachine == null) {
                logger.error("Factory returned a null State Machine for order {}", orderId);
                return null;
            }
            
            // Stop it if it's running
            try {
                stateMachine.stop();
            } catch (Exception e) {
                // Ignore errors while stopping
            }

            // Reset to the current state
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
            
            // Start the State Machine
            stateMachine.start();

            // Check whether the state was configured correctly
            if (stateMachine.getState() == null) {
                logger.error("State Machine created but state is NULL for order {}", orderId);
                // Try to force the state
                try {
                    Thread.sleep(50); // Brief pause
                    stateMachine.start(); // Try starting again
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            
            logger.debug("State Machine successfully created for order {}", orderId);
            return stateMachine;

        } catch (Exception e) {
            logger.error("Error creating State Machine for order {}", orderId, e);
            return null;
        }
    }
    
    private void logCurrentState(StateMachine<OrderState, OrderEvent> stateMachine, String momento) {
        try {
            if (stateMachine.getState() != null && stateMachine.getState().getId() != null) {
                logger.info("State {}: {}", momento, stateMachine.getState().getId());
            } else {
                logger.warn("State {}: NULL (State Machine may not be initialized)", momento);
                logger.debug("State Machine details - ID: {}, UUID: {}, Complete: {}",
                    stateMachine.getId(), stateMachine.getUuid(), stateMachine.isComplete());
            }
        } catch (Exception e) {
            logger.error("Error logging state: {}", e.getMessage());
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
                
                logger.info("State saved to database: {}", newState);

            } else {
                logger.error("Could not update state: State Machine returned a NULL state");
            }
        } catch (Exception e) {
            logger.error("Error updating state in database", e);
            throw e;
        }
    }
    
    // Helper method for debugging
    public void debugStateMachine(String orderId) {
        try {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

            logger.info("=== DEBUG STATE MACHINE ===");
            logger.info("Order ID: {}", orderId);
            logger.info("State in database: {}", order.getState());

            StateMachine<OrderState, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(orderId);

            if (stateMachine == null) {
                logger.info("State Machine: NULL (factory returned null)");
            } else {
                logger.info("State Machine ID: {}", stateMachine.getId());
                logger.info("State Machine UUID: {}", stateMachine.getUuid());
                logger.info("State Machine Complete: {}", stateMachine.isComplete());

                if (stateMachine.getState() != null) {
                    logger.info("Current state: {}", stateMachine.getState().getId());
                } else {
                    logger.info("Current state: NULL");
                }

                // Log all available transitions
                logger.info("Available transitions:");
                stateMachine.getTransitions().forEach(t ->
                    logger.info("  {} -> {} via {}",
                        t.getSource() != null ? t.getSource().getId() : "null",
                        t.getTarget() != null ? t.getTarget().getId() : "null",
                        t.getTrigger() != null ? t.getTrigger().getEvent() : "null")
                );
            }
            logger.info("==========================");
            
        } catch (Exception e) {
            logger.error("Error in debug", e);
        }
    }

    public StateMachine<OrderState, OrderEvent> createStateMachine(String orderId) {
        StateMachine<OrderState, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(orderId);
        stateMachine.start();
        
        // Restore state from the database if it exists
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
            logger.error("Error getting current state of order {}", orderId, e);
            return null;
        }
    }
}