package com.easydora.billing.controller;

import com.easydora.billing.config.JwtAuthenticationFilter;
import com.easydora.billing.config.SecurityConfig;
import com.easydora.billing.service.PaymentService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves /api/payments now authenticates via the JWT broadcast cache instead
 * of Spring Boot's default single-user Basic auth (ADR-0015) -- coverage the
 * original billing-service Basic Auth fix (ADR-0013) never had, since that
 * one was only verified live via curl.
 */
@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PaymentControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private PaymentService paymentService;

    @Test
    void rejectsRequestWithNoToken() throws Exception {
        // No Authorization header at all: the filter never engages, and
        // Spring Security's default AccessDeniedHandler rejects the
        // unauthenticated request with 403 (there is no login mechanism
        // configured to redirect to instead).
        mockMvc.perform(get("/api/payments"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsRequestWithUnknownToken() throws Exception {
        // A Bearer token that isn't in the cache: JwtAuthenticationFilter
        // no longer short-circuits itself (consistency fix matching
        // products-service/orders-service, see ADR-0026) -- it logs and
        // lets the chain continue unauthenticated, so this now converges
        // on the exact same 403 as rejectsRequestWithNoToken above,
        // decided by Spring Security's own authorizeHttpRequests() rather
        // than by two different code paths returning two different status
        // codes for what is functionally the same outcome.
        mockMvc.perform(get("/api/payments").header("Authorization", "Bearer not-a-cached-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsRequestWithAValidCachedToken() throws Exception {
        when(paymentService.findAllForUser(1L)).thenReturn(List.of());
        jwtAuthenticationFilter.addValidToken("tok-1",
                new JwtAuthenticationFilter.JwtUserInfo(1L, "buyer@example.com", "Ana", "Silva", "BUYER"));

        mockMvc.perform(get("/api/payments").header("Authorization", "Bearer tok-1"))
                .andExpect(status().isOk());
    }
}
