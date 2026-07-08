package com.easydora.orders.support;

import com.easydora.orders.config.RabbitMQConfig;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Test-only queue bound to order.exchange/order.status-changed, used to
 * directly observe that a real order.status-changed publish actually
 * happened -- rather than only inferring it from the resulting Order.state
 * in Postgres. No production queue or bean is touched.
 */
@Configuration
public class OrderStatusChangedProbeSupport {

    public static final String PROBE_QUEUE = "orders.test.order-status-changed.probe.queue";

    @Bean
    public Queue orderStatusChangedProbeQueue() {
        return new Queue(PROBE_QUEUE, true);
    }

    @Bean
    public Binding orderStatusChangedProbeBinding(Queue orderStatusChangedProbeQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(orderStatusChangedProbeQueue)
                .to(orderExchange)
                .with(RabbitMQConfig.ORDER_STATUS_CHANGED_KEY);
    }
}
