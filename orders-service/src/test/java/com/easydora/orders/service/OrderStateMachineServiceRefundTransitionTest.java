package com.easydora.orders.service;

import com.easydora.orders.entity.Order;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.statemachine.OrderEvent;
import com.easydora.orders.statemachine.OrderState;
import com.easydora.orders.statemachine.OrderStateMachineConfig;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spike for Etapa 39 (ADR-0034): INVENTORY_FAILED/CANCELLED are configured as
 * .end() states today. Before building the whole compensation flow on top of
 * an assumption, this confirms that removing that declaration and adding a
 * real outgoing transition actually works at runtime -- not just in the
 * static transition graph (OrderStateMachineServiceTransitionTest only
 * checks that), but through the exact sendEvent/resetStateMachine path
 * OrderService uses in production. Fails today (accepted == false) because
 * the transition doesn't exist yet; must go green once
 * OrderStateMachineConfig adds it.
 */
@SpringJUnitConfig(classes = OrderStateMachineConfig.class)
class OrderStateMachineServiceRefundTransitionTest {

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private OrderRepository orderRepositoryBean;

    @ParameterizedTest
    @EnumSource(value = OrderState.class, names = {"INVENTORY_FAILED", "CANCELLED"})
    void aFormerEndStateStillAcceptsARealOutgoingTransition(
            OrderState formerEndState,
            @Autowired StateMachineFactory<OrderState, OrderEvent> factory) {
        Order order = new Order();
        order.setId("order-1");
        order.setState(formerEndState);

        OrderRepository repository = mock(OrderRepository.class);
        when(repository.findById("order-1")).thenReturn(Optional.of(order));
        when(repository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderStateMachineService service = new OrderStateMachineService(factory, repository);

        boolean accepted = service.sendEvent("order-1", OrderEvent.INITIATE_REFUND);

        assertThat(accepted).isTrue();
        assertThat(order.getState()).isEqualTo(OrderState.REFUNDING);
    }
}
