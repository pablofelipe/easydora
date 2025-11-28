package com.easydora.billing.service.provider;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Primary;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@Primary
public class PaymentMockService implements PaymentProvider {
    
    @Override
    public PaymentResult processPayment(Long orderId, BigDecimal amount) {
        
        boolean isApproved = amount.remainder(BigDecimal.valueOf(2)).compareTo(BigDecimal.ZERO) == 0;
        
        if (isApproved) {
            return PaymentResult.approved("TXN_" + UUID.randomUUID().toString().substring(0, 8));
        } else {
            return PaymentResult.failed("Valor ímpar rejeitado pela política mock");
        }
    }
}