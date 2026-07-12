package com.easydora.orders.support;

import com.easydora.orders.config.RabbitMQConfig;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Test-only queue bound to order.exchange/payment.refund.requested (ADR-0034),
 * used to directly observe that OrderService really published a
 * RefundPaymentCommand -- mirroring OrderStatusChangedProbeSupport's shape.
 * No production queue or bean is touched.
 */
@Configuration
public class RefundPaymentRequestedProbeSupport {

    public static final String PROBE_QUEUE = "orders.test.payment-refund-requested.probe.queue";

    @Bean
    public Queue refundPaymentRequestedProbeQueue() {
        return new Queue(PROBE_QUEUE, true);
    }

    @Bean
    public Binding refundPaymentRequestedProbeBinding(Queue refundPaymentRequestedProbeQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(refundPaymentRequestedProbeQueue)
                .to(orderExchange)
                .with(RabbitMQConfig.REFUND_PAYMENT_REQUESTED_KEY);
    }
}
