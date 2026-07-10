package com.easydora.billing.controller;

import com.easydora.billing.config.JwtAuthenticationFilter;
import com.easydora.billing.config.SecurityConfig;
import com.easydora.billing.dto.PaymentDTO;
import com.easydora.billing.service.PaymentService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves every /api/payments endpoint enforces ownership (payment.userId ==
 * authenticated principal's userId), deriving identity exclusively from the
 * JWT broadcast cache -- never from a client-supplied header, mirroring
 * ADR-0027's fix for orders-service/products-service.
 */
@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PaymentControllerOwnershipTest {

    private static final Long OWNER_USER_ID = 65L;
    private static final Long OTHER_USER_ID = 66L;
    private static final String OWNER_TOKEN = "owner-token";
    private static final String OTHER_TOKEN = "other-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private PaymentService paymentService;

    private void authenticateAsOwnerAndOther() {
        jwtAuthenticationFilter.addValidToken(OWNER_TOKEN,
                new JwtAuthenticationFilter.JwtUserInfo(OWNER_USER_ID, "owner@example.com", "Owner", "Buyer", "BUYER"));
        jwtAuthenticationFilter.addValidToken(OTHER_TOKEN,
                new JwtAuthenticationFilter.JwtUserInfo(OTHER_USER_ID, "other@example.com", "Other", "Buyer", "BUYER"));
    }

    private PaymentDTO ownerPayment(Long id, String orderId) {
        PaymentDTO dto = new PaymentDTO();
        dto.setId(id);
        dto.setOrderId(orderId);
        dto.setUserId(OWNER_USER_ID);
        dto.setAmount(new BigDecimal("10.00"));
        dto.setStatus("PENDING");
        return dto;
    }

    @Test
    void getAllPayments_returnsOnlyTheAuthenticatedUsersOwnPayments() throws Exception {
        authenticateAsOwnerAndOther();
        when(paymentService.findAllForUser(OWNER_USER_ID)).thenReturn(List.of(ownerPayment(1L, "order-1")));

        mockMvc.perform(get("/api/payments").header("Authorization", "Bearer " + OWNER_TOKEN))
                .andExpect(status().isOk());

        verify(paymentService).findAllForUser(OWNER_USER_ID);
    }

    @Test
    void getPaymentById_ownerIsAllowed() throws Exception {
        authenticateAsOwnerAndOther();
        when(paymentService.findById(1L)).thenReturn(ownerPayment(1L, "order-1"));

        mockMvc.perform(get("/api/payments/1").header("Authorization", "Bearer " + OWNER_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void getPaymentById_nonOwnerIsForbidden() throws Exception {
        authenticateAsOwnerAndOther();
        when(paymentService.findById(1L)).thenReturn(ownerPayment(1L, "order-1"));

        mockMvc.perform(get("/api/payments/1").header("Authorization", "Bearer " + OTHER_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPaymentByOrderId_ownerIsAllowed() throws Exception {
        authenticateAsOwnerAndOther();
        when(paymentService.findByOrderId("order-1")).thenReturn(ownerPayment(1L, "order-1"));

        mockMvc.perform(get("/api/payments/order/order-1").header("Authorization", "Bearer " + OWNER_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void getPaymentByOrderId_nonOwnerIsForbidden() throws Exception {
        authenticateAsOwnerAndOther();
        when(paymentService.findByOrderId("order-1")).thenReturn(ownerPayment(1L, "order-1"));

        mockMvc.perform(get("/api/payments/order/order-1").header("Authorization", "Bearer " + OTHER_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletePayment_ownerIsAllowed() throws Exception {
        authenticateAsOwnerAndOther();
        when(paymentService.findById(1L)).thenReturn(ownerPayment(1L, "order-1"));

        mockMvc.perform(delete("/api/payments/1").header("Authorization", "Bearer " + OWNER_TOKEN))
                .andExpect(status().isOk());

        verify(paymentService).deletePayment(1L);
    }

    @Test
    void deletePayment_nonOwnerIsForbidden() throws Exception {
        authenticateAsOwnerAndOther();
        when(paymentService.findById(1L)).thenReturn(ownerPayment(1L, "order-1"));

        mockMvc.perform(delete("/api/payments/1").header("Authorization", "Bearer " + OTHER_TOKEN))
                .andExpect(status().isForbidden());

        verify(paymentService, never()).deletePayment(eq(1L));
    }
}
