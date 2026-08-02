package com.easydora.orders.controller;

import com.easydora.orders.config.JwtAuthenticationFilter.JwtUserInfo;
import com.easydora.orders.dto.OrderResponse;
import com.easydora.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ship/fulfillment-queue are platform-operations actions gated by the
 * ADMIN role (not ownership, since no single seller owns a whole order --
 * see OrderService.shipOrder). deliver stays ownership-gated, same as
 * cancelOrder, since the buyer is always well-defined.
 */
@WebMvcTest(OrderController.class)
class OrderFulfillmentControllerTest {

    private static final Long BUYER_ID = 111L;
    private static final Long ADMIN_ID = 900L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private DataSource dataSource;

    private Authentication authenticationFor(Long userId, String role) {
        JwtUserInfo principal = new JwtUserInfo(userId, userId + "@example.com", "First", "Last", role, true);
        return new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void shipOrder_asBuyer_isForbidden() throws Exception {
        mockMvc.perform(post("/order-1/ship")
                .with(authentication(authenticationFor(BUYER_ID, "BUYER")))
                .with(csrf()))
            .andExpect(status().isForbidden());

        verify(orderService, never()).shipOrder(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shipOrder_asSeller_isForbidden() throws Exception {
        mockMvc.perform(post("/order-1/ship")
                .with(authentication(authenticationFor(BUYER_ID, "SELLER")))
                .with(csrf()))
            .andExpect(status().isForbidden());

        verify(orderService, never()).shipOrder(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shipOrder_asAdmin_succeeds() throws Exception {
        OrderResponse response = new OrderResponse();
        response.setId("order-1");
        when(orderService.shipOrder("order-1")).thenReturn(response);

        mockMvc.perform(post("/order-1/ship")
                .with(authentication(authenticationFor(ADMIN_ID, "ADMIN")))
                .with(csrf()))
            .andExpect(status().isOk());

        verify(orderService).shipOrder("order-1");
    }

    @Test
    void deliverOrder_usesAuthenticatedPrincipalOwnership() throws Exception {
        OrderResponse response = new OrderResponse();
        response.setId("order-1");
        when(orderService.deliverOrder("order-1", BUYER_ID)).thenReturn(response);

        mockMvc.perform(post("/order-1/deliver")
                .header("X-User-Id", "999")
                .with(authentication(authenticationFor(BUYER_ID, "BUYER")))
                .with(csrf()))
            .andExpect(status().isOk());

        verify(orderService).deliverOrder("order-1", BUYER_ID);
        verify(orderService, never()).deliverOrder(eq("order-1"), eq(999L));
    }

    @Test
    void fulfillmentQueue_asBuyer_isForbidden() throws Exception {
        mockMvc.perform(get("/fulfillment")
                .with(authentication(authenticationFor(BUYER_ID, "BUYER"))))
            .andExpect(status().isForbidden());

        verify(orderService, never()).getFulfillmentQueue();
    }

    @Test
    void fulfillmentQueue_asAdmin_succeeds() throws Exception {
        when(orderService.getFulfillmentQueue()).thenReturn(List.of());

        mockMvc.perform(get("/fulfillment")
                .with(authentication(authenticationFor(ADMIN_ID, "ADMIN"))))
            .andExpect(status().isOk());

        verify(orderService).getFulfillmentQueue();
    }

    // ADR-0034's remediation-tooling follow-up: same ADMIN-only,
    // platform-operations gate as fulfillment/ship above.
    @Test
    void refundFailedQueue_asBuyer_isForbidden() throws Exception {
        mockMvc.perform(get("/refunds/failed")
                .with(authentication(authenticationFor(BUYER_ID, "BUYER"))))
            .andExpect(status().isForbidden());

        verify(orderService, never()).getRefundFailedQueue();
    }

    @Test
    void refundFailedQueue_asAdmin_succeeds() throws Exception {
        when(orderService.getRefundFailedQueue()).thenReturn(List.of());

        mockMvc.perform(get("/refunds/failed")
                .with(authentication(authenticationFor(ADMIN_ID, "ADMIN"))))
            .andExpect(status().isOk());

        verify(orderService).getRefundFailedQueue();
    }

    @Test
    void retryRefund_asBuyer_isForbidden() throws Exception {
        mockMvc.perform(post("/order-1/retry-refund")
                .with(authentication(authenticationFor(BUYER_ID, "BUYER")))
                .with(csrf()))
            .andExpect(status().isForbidden());

        verify(orderService, never()).retryRefund(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void retryRefund_asAdmin_succeeds() throws Exception {
        OrderResponse response = new OrderResponse();
        response.setId("order-1");
        when(orderService.retryRefund("order-1")).thenReturn(response);

        mockMvc.perform(post("/order-1/retry-refund")
                .with(authentication(authenticationFor(ADMIN_ID, "ADMIN")))
                .with(csrf()))
            .andExpect(status().isOk());

        verify(orderService).retryRefund("order-1");
    }
}
