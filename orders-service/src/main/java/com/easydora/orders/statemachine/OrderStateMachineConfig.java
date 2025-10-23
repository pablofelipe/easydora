package com.easydora.orders.statemachine;

import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

@Configuration
@EnableStateMachineFactory
public class OrderStateMachineConfig extends StateMachineConfigurerAdapter<OrderState, OrderEvent> {

    @Override
    public void configure(StateMachineStateConfigurer<OrderState, OrderEvent> states) throws Exception {
        states
            .withStates()
                .initial(OrderState.PENDING)
                .states(EnumSet.allOf(OrderState.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderState, OrderEvent> transitions) throws Exception {
        transitions
            .withExternal()
                .source(OrderState.PENDING).target(OrderState.PAYMENT_APPROVED)
                .event(OrderEvent.PAYMENT_RECEIVED)
                .and()
            .withExternal()
                .source(OrderState.PENDING).target(OrderState.PAYMENT_FAILED)
                .event(OrderEvent.PAYMENT_FAILED)
                .and()
            .withExternal()
                .source(OrderState.PAYMENT_APPROVED).target(OrderState.PROCESSING)
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
                .source(OrderState.INVENTORY_RESERVED).target(OrderState.SHIPPED)
                .event(OrderEvent.SHIP_ORDER)
                .and()
            .withExternal()
                .source(OrderState.SHIPPED).target(OrderState.DELIVERED)
                .event(OrderEvent.DELIVER_ORDER)
                .and()
            .withExternal()
                .source(OrderState.PENDING).target(OrderState.CANCELLED)
                .event(OrderEvent.CANCEL_ORDER)
                .and()
            .withExternal()
                .source(OrderState.PAYMENT_APPROVED).target(OrderState.CANCELLED)
                .event(OrderEvent.CANCEL_ORDER);
    }
}