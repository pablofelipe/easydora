package com.easydora.billing.service;

import com.easydora.billing.dto.PaymentDTO;
import com.easydora.billing.exception.PaymentNotFoundException;
import com.easydora.billing.model.Payment;
import com.easydora.billing.repository.PaymentRepository;
import com.easydora.billing.service.provider.PaymentMockService;
import com.easydora.billing.service.provider.PaymentProvider;
import com.easydora.billing.service.provider.PaymentResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * PaymentService must delegate the approval decision entirely to
 * PaymentProvider -- never decide with Math.random() itself. The fake
 * provider (PaymentMockService) is real here, not mocked, because the
 * property under test is the actual wiring end to end: the same order
 * (same amount) always resolves the same way, and it's driven solely by
 * the provider's own rule (amount parity), not by anything PaymentService
 * does on its own.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceDeterministicApprovalTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private final PaymentProvider paymentProvider = new PaymentMockService();

    private PaymentService newPaymentService() {
        return new PaymentService(paymentRepository, rabbitTemplate, paymentProvider);
    }

    @Test
    void evenAmountIsApproved() {
        Payment existing = new Payment("order-even", new BigDecimal("100.00"));
        when(paymentRepository.findByOrderId("order-even")).thenReturn(Optional.of(existing));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentDTO result = newPaymentService().processPayment("order-even");

        assertThat(result.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void oddAmountIsRejected() {
        Payment existing = new Payment("order-odd", new BigDecimal("99.00"));
        when(paymentRepository.findByOrderId("order-odd")).thenReturn(Optional.of(existing));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentDTO result = newPaymentService().processPayment("order-odd");

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getFailureReason()).isNotBlank();
    }

    @Test
    void processingAnOrderWithNoExistingPaymentThrowsADomainNotFoundError() {
        when(paymentRepository.findByOrderId("order-never-created")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newPaymentService().processPayment("order-never-created"))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void theProviderItselfIsAPureFunctionOfAmount() {
        PaymentResult first = paymentProvider.processPayment("order-a", new BigDecimal("50.00"));
        PaymentResult second = paymentProvider.processPayment("order-b", new BigDecimal("50.00"));
        PaymentResult third = paymentProvider.processPayment("order-a", new BigDecimal("51.00"));

        assertThat(first.isApproved()).isTrue();
        assertThat(second.isApproved()).isTrue();
        assertThat(third.isApproved()).isFalse();
    }

    @Test
    void retryingAFailedPaymentForTheSameOrderRepeatsTheSameOutcome() {
        Payment existing = new Payment("order-retry", new BigDecimal("13.00"));
        when(paymentRepository.findByOrderId("order-retry")).thenReturn(Optional.of(existing));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentService paymentService = newPaymentService();

        PaymentDTO firstAttempt = paymentService.processPayment("order-retry");
        PaymentDTO secondAttempt = paymentService.processPayment("order-retry");

        assertThat(firstAttempt.getStatus()).isEqualTo("FAILED");
        assertThat(secondAttempt.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void paymentServiceSourceNeverReferencesMathRandom() throws Exception {
        Path sourceFile = Path.of("src/main/java/com/easydora/billing/service/PaymentService.java");
        String source = Files.readString(sourceFile, StandardCharsets.UTF_8);

        assertThat(source)
                .withFailMessage("PaymentService must delegate every approval decision to PaymentProvider, never Math.random() directly")
                .doesNotContain("Math.random");
    }
}
