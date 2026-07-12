package com.easydora.billing.service;

import com.easydora.billing.model.Payment;
import com.easydora.billing.model.PaymentStatus;
import com.easydora.billing.repository.PaymentRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves Payment's @Version column (ADR-0033) rejects a stale concurrent
 * write against a real Postgres row -- the same shape a duplicated
 * gateway callback or a client retry racing the original /process call
 * would produce. See OrderOptimisticLockingIT for why two sequential,
 * independently loaded copies are sufficient to reproduce this without
 * real threads.
 */
@SpringBootTest
class PaymentOptimisticLockingIT {

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void secondConcurrentWriteIsRejectedAndFirstWriteIsNotLost() {
        String orderId = "it-" + UUID.randomUUID();
        Payment payment = new Payment(orderId, new BigDecimal("10.00"));
        payment.setUserId(1L);
        paymentRepository.saveAndFlush(payment);

        Payment firstReader = paymentRepository.findByOrderId(orderId).orElseThrow();
        Payment secondReader = paymentRepository.findByOrderId(orderId).orElseThrow();

        firstReader.setStatus(PaymentStatus.APPROVED);
        paymentRepository.saveAndFlush(firstReader);

        secondReader.setStatus(PaymentStatus.FAILED);
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(secondReader))
                .isInstanceOf(OptimisticLockingFailureException.class);

        Payment persisted = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(persisted.getStatus())
                .withFailMessage("the first writer's update must not be silently lost")
                .isEqualTo(PaymentStatus.APPROVED);
    }
}
