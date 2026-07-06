package com.easydora.orders.consumer;

import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.entity.Order;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.statemachine.OrderState;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives inventory-service's outbox-published stock.reserved/stock.insufficient
 * events (ADR-0007) through the real order.exchange and asserts
 * InventoryEventsConsumer applies the outcome to the order's real state
 * machine and persists it to a real Postgres (CI Phase 2 service
 * containers). inventory-service itself is not involved — this test
 * publishes the same event shape its Outbox would, to prove orders-service's
 * consumer side of the handshake independently.
 *
 * {@code @DirtiesContext} is load-bearing, not decorative: Failsafe reuses
 * one JVM across every *IT class by default, and without it Spring's
 * context cache would keep this class's real JwtConsumer/UserEventsConsumer
 * listener containers running for the rest of that JVM — a live competing
 * consumer on the exact queues JwtCreatedFanoutIT drains directly, the same
 * failure mode ADR-0001/ADR-0008 documented, just self-inflicted between
 * two test classes instead of by an external docker-compose stack.
 */
@SpringBootTest
@DirtiesContext
class StockOutcomeWiringIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void stockReservedEventMovesOrderToInventoryReserved() throws Exception {
        String orderId = "it-" + UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(1L);
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setState(OrderState.PROCESSING);
        orderRepository.save(order);

        String body = "{\"orderId\":\"" + orderId + "\",\"success\":true,\"message\":\"stock reserved\"}";
        rabbitTemplate.send(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.STOCK_RESERVED_ROUTING_KEY,
                new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        OrderState finalState = awaitState(orderId);
        assertThat(finalState)
                .withFailMessage("order %s should have transitioned to INVENTORY_RESERVED after a real stock.reserved publish", orderId)
                .isEqualTo(OrderState.INVENTORY_RESERVED);
    }

    @Test
    void stockInsufficientEventMovesOrderToInventoryFailed() throws Exception {
        String orderId = "it-" + UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(1L);
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setState(OrderState.PROCESSING);
        orderRepository.save(order);

        String body = "{\"orderId\":\"" + orderId + "\",\"productId\":\"prod-1\",\"required\":5,\"available\":1}";
        rabbitTemplate.send(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.STOCK_INSUFFICIENT_ROUTING_KEY,
                new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        OrderState finalState = awaitState(orderId);
        assertThat(finalState)
                .withFailMessage("order %s should have transitioned to INVENTORY_FAILED after a real stock.insufficient publish", orderId)
                .isEqualTo(OrderState.INVENTORY_FAILED);
    }

    private OrderState awaitState(String orderId) throws InterruptedException {
        OrderState state = OrderState.PROCESSING;
        for (int i = 0; i < 20; i++) {
            Optional<Order> current = orderRepository.findById(orderId);
            if (current.isPresent()) {
                state = current.get().getState();
                if (state != OrderState.PROCESSING) {
                    return state;
                }
            }
            Thread.sleep(250);
        }
        return state;
    }
}
