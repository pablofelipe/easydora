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
                .end(OrderState.CANCELLED)
                .end(OrderState.PAYMENT_FAILED)
                .end(OrderState.INVENTORY_FAILED);
    }

    @Bean
    public ReleaseInventoryAction releaseInventoryAction() {
        return new ReleaseInventoryAction();
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderState, OrderEvent> transitions) throws Exception {
        transitions
            // 1. Fluxo de pagamento
            .withExternal()
                .source(OrderState.PENDING).target(OrderState.PAYMENT_APPROVED)
                .event(OrderEvent.PAYMENT_RECEIVED)
            .and()
            .withExternal()
                .source(OrderState.PENDING).target(OrderState.PAYMENT_FAILED)
                .event(OrderEvent.PAYMENT_FAILED)
            .and()

            // 2. Transição automática para PROCESSING após pagamento aprovado
            .withExternal()
                .source(OrderState.PAYMENT_APPROVED).target(OrderState.PROCESSING)
                .event(OrderEvent.START_PROCESSING)
            .and()

            // 3. Fluxo de inventário
            .withExternal()
                .source(OrderState.PROCESSING).target(OrderState.INVENTORY_RESERVED)
                .event(OrderEvent.INVENTORY_RESERVED)
            .and()
            .withExternal()
                .source(OrderState.PROCESSING).target(OrderState.INVENTORY_FAILED)
                .event(OrderEvent.INVENTORY_FAILED)
            .and()

            // 4. Fluxo de envio
            .withExternal()
                .source(OrderState.INVENTORY_RESERVED).target(OrderState.SHIPPED)
                .event(OrderEvent.SHIP_ORDER)
            .and()
            .withExternal()
                .source(OrderState.SHIPPED).target(OrderState.DELIVERED)
                .event(OrderEvent.DELIVER_ORDER)
            .and()

            // 5. Cancelamento (vários estados)
            .withExternal()
                .source(OrderState.PENDING).target(OrderState.CANCELLED)
                .event(OrderEvent.CANCEL_ORDER)
            .and()
            .withExternal()
                .source(OrderState.PAYMENT_APPROVED).target(OrderState.CANCELLED)
                .event(OrderEvent.CANCEL_ORDER)
            .and()
            .withExternal()
                .source(OrderState.INVENTORY_RESERVED).target(OrderState.CANCELLED)
                .event(OrderEvent.CANCEL_ORDER)
                .action(releaseInventoryAction())
            .and()

            // 6. Reembolso
            .withExternal()
                .source(OrderState.PAYMENT_APPROVED).target(OrderState.REFUNDING)
                .event(OrderEvent.INITIATE_REFUND)
            .and()
            .withExternal()
                .source(OrderState.REFUNDING).target(OrderState.CANCELLED)
                .event(OrderEvent.REFUND_COMPLETED);
    }
}