package com.easydora.billing.service.provider;

import java.math.BigDecimal;

public interface PaymentProvider {
    PaymentResult processPayment(String orderId, BigDecimal amount);

    // ADR-0034: reuses PaymentResult -- a refund outcome is the same shape
    // (success/failure + optional reference + optional reason) as a charge
    // outcome, and this project already removed one duplicate PaymentResult
    // class (ADR-0030) for exactly this kind of near-identical duplication.
    // transactionId is the original charge's reference, passed through in
    // case a real provider needs it to look up what to reverse.
    PaymentResult refund(String orderId, String transactionId, BigDecimal amount);
}

