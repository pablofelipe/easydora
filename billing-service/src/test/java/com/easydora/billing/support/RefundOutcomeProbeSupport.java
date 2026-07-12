package com.easydora.billing.support;

import com.easydora.billing.config.RabbitMQConfig;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Test-only queues bound to order.exchange/payment.refunded and
 * order.exchange/payment.refund.failed (ADR-0034), used to directly observe
 * that PaymentService.refundPayment really published one of the two
 * outcomes -- mirroring orders-service's OrderStatusChangedProbeSupport
 * shape. No production queue or bean is touched.
 */
@Configuration
public class RefundOutcomeProbeSupport {

    public static final String PAYMENT_REFUNDED_PROBE_QUEUE = "billing.test.payment-refunded.probe.queue";
    public static final String PAYMENT_REFUND_FAILED_PROBE_QUEUE = "billing.test.payment-refund-failed.probe.queue";

    @Bean
    public Queue paymentRefundedProbeQueue() {
        return new Queue(PAYMENT_REFUNDED_PROBE_QUEUE, true);
    }

    @Bean
    public Binding paymentRefundedProbeBinding(Queue paymentRefundedProbeQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(paymentRefundedProbeQueue)
                .to(orderExchange)
                .with(RabbitMQConfig.PAYMENT_REFUNDED_KEY);
    }

    @Bean
    public Queue paymentRefundFailedProbeQueue() {
        return new Queue(PAYMENT_REFUND_FAILED_PROBE_QUEUE, true);
    }

    @Bean
    public Binding paymentRefundFailedProbeBinding(Queue paymentRefundFailedProbeQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(paymentRefundFailedProbeQueue)
                .to(orderExchange)
                .with(RabbitMQConfig.PAYMENT_REFUND_FAILED_KEY);
    }
}
