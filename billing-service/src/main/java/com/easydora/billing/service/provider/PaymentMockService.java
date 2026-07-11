package com.easydora.billing.service.provider;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Primary;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@Primary
public class PaymentMockService implements PaymentProvider {

    // orderId is intentionally unused by this decision -- kept in the
    // contract because a real provider would need it (idempotency,
    // reference), but this fake's rule is deliberately the simplest
    // deterministic thing that still varies by order: parity of the
    // amount itself, not a hash of the id. Same order (same amount)
    // always resolves the same way; a different amount can resolve
    // differently.
    @Override
    public PaymentResult processPayment(String orderId, BigDecimal amount) {

        boolean isApproved = amount.remainder(BigDecimal.valueOf(2)).compareTo(BigDecimal.ZERO) == 0;

        if (isApproved) {
            return PaymentResult.approved("TXN_" + UUID.randomUUID().toString().substring(0, 8));
        } else {
            return PaymentResult.failed("Odd amount rejected by the mock policy");
        }
    }
}