package com.easydora.billing.service.provider;

import java.math.BigDecimal;

public interface PaymentProvider {
    PaymentResult processPayment(String orderId, BigDecimal amount);
}

