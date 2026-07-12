package com.easydora.orders.statemachine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import com.easydora.orders.actions.ReleaseInventoryAction;

import java.util.EnumSet;

@Configuration
@EnableStateMachineFactory
public class OrderStateMachineConfig extends StateMachineConfigurerAdapter<OrderState, OrderEvent> {

    @Override
    public void configure(StateMachineStateConfigurer<OrderState, OrderEvent> states) throws Exception {
        states
            .withStates()
                .initial(OrderState.PENDING)
                .states(EnumSet.allOf(OrderState.class))
                .end(OrderState.DELIVERED)
                .end(OrderState.PAYMENT_FAILED)
                // INVENTORY_FAILED/CANCELLED are no longer unconditionally
                // final (ADR-0034): each still ends the order's own
                // lifecycle by default, but now has one outgoing edge
                // (INITIATE_REFUND) for the case a payment.approved arrives
                // for an order that already reached one of these states --
                // see OrderService.handlePaymentReceived.
                .end(OrderState.REFUNDED)
                .end(OrderState.REFUND_FAILED);
    }

    @Bean
    public ReleaseInventoryAction releaseInventoryAction() {
        return new ReleaseInventoryAction();
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderState, OrderEvent> transitions) throws Exception {
        transitions
            .withExternal()
                .source(OrderState.PENDING).target(OrderState.PROCESSING)
                .event(OrderEvent.START_PROCESSING)
            .and()
            .withExternal()
                .source(OrderState.PROCESSING).target(OrderState.INVENTORY_RESERVED)
                .event(OrderEvent.INVENTORY_RESERVED)
            .and()
            .withExternal()
                .source(OrderState.PROCESSING).target(OrderState.INVENTORY_FAILED)
                .event(OrderEvent.INVENTORY_FAILED)
            .and()
            .withExternal()
                .source(OrderState.INVENTORY_RESERVED).target(OrderState.PAYMENT_APPROVED)
                .event(OrderEvent.PAYMENT_RECEIVED)
            .and()
            .withExternal()
                .source(OrderState.INVENTORY_RESERVED).target(OrderState.PAYMENT_FAILED)
                .event(OrderEvent.PAYMENT_FAILED)
                .action(releaseInventoryAction())
            .and()
            .withExternal()
                .source(OrderState.PAYMENT_APPROVED).target(OrderState.SHIPPED)
                .event(OrderEvent.SHIP_ORDER)
            .and()
            .withExternal()
                .source(OrderState.SHIPPED).target(OrderState.DELIVERED)
                .event(OrderEvent.DELIVER_ORDER)
            .and()
            
            // FALHAS DIRETAS
            .withExternal()
                .source(OrderState.PENDING).target(OrderState.INVENTORY_FAILED)
                .event(OrderEvent.INVENTORY_FAILED)
            .and()
            
            // CANCELAMENTOS
            .withExternal()
                .source(OrderState.PENDING).target(OrderState.CANCELLED)
                .event(OrderEvent.CANCEL_ORDER)
            .and()
            .withExternal()
                .source(OrderState.PROCESSING).target(OrderState.CANCELLED)
                .event(OrderEvent.CANCEL_ORDER)
            .and()
            .withExternal()
                .source(OrderState.INVENTORY_RESERVED).target(OrderState.CANCELLED)
                .event(OrderEvent.CANCEL_ORDER)
                .action(releaseInventoryAction())
            .and()

            // PAYMENT COMPENSATION (ADR-0034): a payment.approved that
            // arrives for an order already in one of these two states means
            // Billing approved a charge this order can no longer honor.
            // Orders is the one that detects this (its own PAYMENT_RECEIVED
            // transition is rejected from here), and is the one that
            // initiates compensation -- Billing never decides this on its
            // own, it only reacts to the request.
            .withExternal()
                .source(OrderState.INVENTORY_FAILED).target(OrderState.REFUNDING)
                .event(OrderEvent.INITIATE_REFUND)
            .and()
            .withExternal()
                .source(OrderState.CANCELLED).target(OrderState.REFUNDING)
                .event(OrderEvent.INITIATE_REFUND)
            .and()
            .withExternal()
                .source(OrderState.REFUNDING).target(OrderState.REFUNDED)
                .event(OrderEvent.REFUND_COMPLETED)
            .and()
            .withExternal()
                .source(OrderState.REFUNDING).target(OrderState.REFUND_FAILED)
                .event(OrderEvent.REFUND_FAILED)
            .and();
    }
}