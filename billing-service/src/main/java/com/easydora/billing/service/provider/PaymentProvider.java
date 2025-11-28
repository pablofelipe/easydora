package com.easydora.billing.service.provider;

import java.math.BigDecimal;

// PaymentProvider.java
public interface PaymentProvider {
    PaymentResult processPayment(Long orderId, BigDecimal amount);
}

