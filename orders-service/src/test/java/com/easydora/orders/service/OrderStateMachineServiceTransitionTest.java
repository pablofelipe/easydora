package com.easydora.orders.service;

import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.statemachine.OrderEvent;
import com.easydora.orders.statemachine.OrderState;
import com.easydora.orders.statemachine.OrderStateMachineConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * isTransitionAllowed must reflect exactly the transition graph configured
 * in OrderStateMachineConfig -- not a hand-written copy of it. Loads the
 * real config (no JPA/DB, no full app context needed: it's a plain
 * @Configuration + @EnableStateMachineFactory) so this fails the moment the
 * config and the check drift apart, the exact bug canCancel() had for
 * PAYMENT_APPROVED before this refactor. RabbitTemplate is
 * mocked only because ReleaseInventoryAction (wired as a state machine
 * action bean) needs one to exist in this slice -- it's never invoked.
 */
@SpringJUnitConfig(classes = OrderStateMachineConfig.class)
class OrderStateMachineServiceTransitionTest {

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private OrderRepository orderRepositoryBean;

    private OrderStateMachineService newService(StateMachineFactory<OrderState, OrderEvent> factory) {
        return new OrderStateMachineService(factory, mock(OrderRepository.class));
    }

    static Stream<Arguments> transitions() {
        return Stream.of(
                // The bug this refactor fixes: canCancel() used to claim
                // PAYMENT_APPROVED was cancellable, but the state machine
                // never had a PAYMENT_APPROVED -> * CANCEL_ORDER transition.
                Arguments.of(OrderState.PAYMENT_APPROVED, OrderEvent.CANCEL_ORDER, false),
                Arguments.of(OrderState.PENDING, OrderEvent.CANCEL_ORDER, true),
                Arguments.of(OrderState.PROCESSING, OrderEvent.CANCEL_ORDER, true),
                Arguments.of(OrderState.INVENTORY_RESERVED, OrderEvent.CANCEL_ORDER, true),
                Arguments.of(OrderState.DELIVERED, OrderEvent.CANCEL_ORDER, false),
                Arguments.of(OrderState.CANCELLED, OrderEvent.CANCEL_ORDER, false),
                // The two transitions this refactor activates.
                Arguments.of(OrderState.PAYMENT_APPROVED, OrderEvent.SHIP_ORDER, true),
                Arguments.of(OrderState.SHIPPED, OrderEvent.DELIVER_ORDER, true),
                Arguments.of(OrderState.PENDING, OrderEvent.SHIP_ORDER, false),
                Arguments.of(OrderState.PAYMENT_APPROVED, OrderEvent.DELIVER_ORDER, false),
                Arguments.of(OrderState.DELIVERED, OrderEvent.DELIVER_ORDER, false),
                // Payment compensation (ADR-0034): INVENTORY_FAILED/CANCELLED
                // are no longer unconditionally final -- each has exactly
                // one outgoing edge for a stray payment.approved.
                Arguments.of(OrderState.INVENTORY_FAILED, OrderEvent.INITIATE_REFUND, true),
                Arguments.of(OrderState.CANCELLED, OrderEvent.INITIATE_REFUND, true),
                Arguments.of(OrderState.REFUNDING, OrderEvent.REFUND_COMPLETED, true),
                Arguments.of(OrderState.REFUNDING, OrderEvent.REFUND_FAILED, true),
                // INITIATE_REFUND is not a general-purpose event -- it must
                // not be reachable from a state that never approved a
                // payment or never got cancelled.
                Arguments.of(OrderState.PENDING, OrderEvent.INITIATE_REFUND, false),
                Arguments.of(OrderState.PAYMENT_APPROVED, OrderEvent.INITIATE_REFUND, false),
                // REFUNDED/REFUND_FAILED are genuinely terminal -- no retry,
                // no re-triggering (see ADR-0034's rationale for not
                // auto-retrying a refund.failed).
                Arguments.of(OrderState.REFUNDED, OrderEvent.REFUND_COMPLETED, false),
                Arguments.of(OrderState.REFUND_FAILED, OrderEvent.INITIATE_REFUND, false)
        );
    }

    @ParameterizedTest
    @MethodSource("transitions")
    void reflectsExactlyWhatTheStateMachineConfigAllows(
            OrderState from, OrderEvent event, boolean expected,
            @org.springframework.beans.factory.annotation.Autowired StateMachineFactory<OrderState, OrderEvent> factory) {
        OrderStateMachineService service = newService(factory);
        assertThat(service.isTransitionAllowed(from, event)).isEqualTo(expected);
    }

    @Test
    void repeatedCallsAgreeWithEachOther(
            @org.springframework.beans.factory.annotation.Autowired StateMachineFactory<OrderState, OrderEvent> factory) {
        OrderStateMachineService service = newService(factory);
        boolean first = service.isTransitionAllowed(OrderState.PENDING, OrderEvent.CANCEL_ORDER);
        boolean second = service.isTransitionAllowed(OrderState.PENDING, OrderEvent.CANCEL_ORDER);
        assertThat(first).isEqualTo(second).isTrue();
    }
}
