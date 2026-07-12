package com.easydora.billing.controller;

import com.easydora.billing.config.JwtAuthenticationFilter;
import com.easydora.billing.config.SecurityConfig;
import com.easydora.billing.service.PaymentService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A concurrent-write conflict on the underlying Payment (ADR-0033's
 * @Version) must map to 409 Conflict at the HTTP boundary too -- the same
 * status orders-service's GlobalExceptionHandler uses, kept uniform across
 * services even though billing-service has no equivalent
 * @RestControllerAdvice (a deliberate choice, see ADR-0031).
 */
@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PaymentControllerOptimisticLockingTest {

    private static final String TOKEN = "some-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private PaymentService paymentService;

    private void authenticate() {
        jwtAuthenticationFilter.addValidToken(TOKEN,
                new JwtAuthenticationFilter.JwtUserInfo(1L, "buyer@example.com", "Buyer", "Buyer", "BUYER"));
    }

    @Test
    void processPayment_concurrentConflictReturnsConflictNotBadRequest() throws Exception {
        authenticate();
        when(paymentService.processPayment("order-1"))
                .thenThrow(new OptimisticLockingFailureException("stale payment"));

        mockMvc.perform(post("/api/payments/process")
                        .param("orderId", "order-1")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isConflict());
    }

    @Test
    void retryPayment_concurrentConflictReturnsConflictNotBadRequest() throws Exception {
        authenticate();
        when(paymentService.retryPayment("order-1"))
                .thenThrow(new OptimisticLockingFailureException("stale payment"));

        mockMvc.perform(post("/api/payments/order-1/retry")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isConflict());
    }
}
