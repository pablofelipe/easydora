package com.easydora.orders.service;

import com.easydora.orders.entity.Order;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.statemachine.OrderState;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves Order's @Version column (ADR-0033) actually rejects a stale
 * concurrent write against a real Postgres row instead of the second
 * writer silently overwriting the first's change. Two independently
 * loaded copies of the same row, saved in sequence, reproduce the same
 * lost-update shape a race between an HTTP request and a RabbitMQ
 * consumer updating the same order would hit -- no real threads needed,
 * since the version check is keyed on the value each copy carries, not
 * on timing.
 */
@SpringBootTest
class OrderOptimisticLockingIT {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void secondConcurrentWriteIsRejectedAndFirstWriteIsNotLost() {
        String orderId = "it-" + UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(1L);
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setState(OrderState.PENDING);
        orderRepository.saveAndFlush(order);

        Order firstReader = orderRepository.findById(orderId).orElseThrow();
        Order secondReader = orderRepository.findById(orderId).orElseThrow();

        firstReader.setState(OrderState.PROCESSING);
        orderRepository.saveAndFlush(firstReader);

        secondReader.setState(OrderState.CANCELLED);
        assertThatThrownBy(() -> orderRepository.saveAndFlush(secondReader))
                .isInstanceOf(OptimisticLockingFailureException.class);

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getState())
                .withFailMessage("the first writer's update must not be silently lost")
                .isEqualTo(OrderState.PROCESSING);
    }
}
