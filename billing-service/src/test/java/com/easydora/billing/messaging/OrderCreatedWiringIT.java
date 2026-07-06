package com.easydora.billing.messaging;

import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.billing.messaging.events.OrderCreatedEvent;
import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.PaymentRepository;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publishes a real order.created event onto the real order.exchange (CI
 * Phase 2 service containers) and asserts OrderEventListener /
 * PaymentService actually persist the pending Payment to a real Postgres.
 * BillingServiceApplicationIT only proves the Spring context boots against
 * live infrastructure; this test proves the order.created wiring itself
 * works end to end.
 */
@SpringBootTest
class OrderCreatedWiringIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void orderCreatedEventResultsInPendingPayment() throws Exception {
        String orderId = "it-" + UUID.randomUUID();

        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId(orderId);
        event.setUserId(42L);
        event.setTotalAmount(new BigDecimal("99.90"));
        event.setItems(List.of());
        event.setCreatedAt(LocalDateTime.now());

        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.ORDER_CREATED_KEY, event);

        Payment payment = awaitPayment(orderId);

        assertThat(payment)
                .withFailMessage("no Payment was persisted for order %s after a real order.created publish", orderId)
                .isNotNull();
        assertThat(payment.getUserId()).isEqualTo(42L);
        assertThat(payment.getAmount()).isEqualByComparingTo("99.90");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    private Payment awaitPayment(String orderId) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Optional<Payment> payment = paymentRepository.findByOrderId(orderId);
            if (payment.isPresent()) {
                return payment.get();
            }
            Thread.sleep(250);
        }
        return null;
    }
}
