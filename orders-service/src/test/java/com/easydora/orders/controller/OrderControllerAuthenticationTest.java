package com.easydora.orders.controller;

import com.easydora.orders.config.JwtAuthenticationFilter.JwtUserInfo;
import com.easydora.orders.dto.OrderItemRequest;
import com.easydora.orders.dto.OrderRequest;
import com.easydora.orders.dto.OrderResponse;
import com.easydora.orders.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that identity used for business decisions comes exclusively from
 * the authenticated JWT principal, never from the client-supplied
 * X-User-Id header. Each test authenticates as user 111 and sends a
 * divergent X-User-Id (999) to demonstrate the header is inert.
 */
@WebMvcTest(OrderController.class)
class OrderControllerAuthenticationTest {

    private static final Long REAL_USER_ID = 111L;
    private static final String SPOOFED_HEADER_USER_ID = "999";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    private Authentication authenticationFor(Long userId) {
        JwtUserInfo principal = new JwtUserInfo(userId, "buyer@example.com", "Real", "Buyer", "BUYER", true);
        return new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_BUYER")));
    }

    @Test
    void getUserOrders_usesAuthenticatedPrincipal_ignoringDivergentHeader() throws Exception {
        when(orderService.getUserOrders(REAL_USER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/user")
                .header("X-User-Id", SPOOFED_HEADER_USER_ID)
                .with(authentication(authenticationFor(REAL_USER_ID))))
            .andExpect(status().isOk());

        verify(orderService).getUserOrders(REAL_USER_ID);
        verify(orderService, never()).getUserOrders(999L);
    }

    @Test
    void getOrder_usesAuthenticatedPrincipal_ignoringDivergentHeader() throws Exception {
        OrderResponse response = new OrderResponse();
        response.setId("order-1");
        when(orderService.getOrder("order-1", REAL_USER_ID)).thenReturn(response);

        mockMvc.perform(get("/order-1")
                .header("X-User-Id", SPOOFED_HEADER_USER_ID)
                .with(authentication(authenticationFor(REAL_USER_ID))))
            .andExpect(status().isOk());

        verify(orderService).getOrder("order-1", REAL_USER_ID);
        verify(orderService, never()).getOrder("order-1", 999L);
    }

    @Test
    void cancelOrder_usesAuthenticatedPrincipal_ignoringDivergentHeader() throws Exception {
        OrderResponse response = new OrderResponse();
        response.setId("order-1");
        when(orderService.cancelOrder("order-1", REAL_USER_ID)).thenReturn(response);

        mockMvc.perform(post("/order-1/cancel")
                .header("X-User-Id", SPOOFED_HEADER_USER_ID)
                .with(authentication(authenticationFor(REAL_USER_ID)))
                .with(csrf()))
            .andExpect(status().isOk());

        verify(orderService).cancelOrder("order-1", REAL_USER_ID);
        verify(orderService, never()).cancelOrder("order-1", 999L);
    }

    @Test
    void createOrder_usesAuthenticatedPrincipal_ignoringDivergentHeader() throws Exception {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId("product-1");
        item.setQuantity(1);
        item.setUnitPrice(new java.math.BigDecimal("10.00"));
        OrderRequest request = new OrderRequest();
        request.setItems(List.of(item));
        OrderResponse response = new OrderResponse();
        response.setId("order-new");
        when(orderService.createOrder(any(OrderRequest.class), org.mockito.ArgumentMatchers.eq(REAL_USER_ID)))
            .thenReturn(response);

        mockMvc.perform(post("/createOrder")
                .header("X-User-Id", SPOOFED_HEADER_USER_ID)
                .with(authentication(authenticationFor(REAL_USER_ID)))
                .with(csrf())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk());

        verify(orderService).createOrder(any(OrderRequest.class), org.mockito.ArgumentMatchers.eq(REAL_USER_ID));
        verify(orderService, never()).createOrder(any(OrderRequest.class), org.mockito.ArgumentMatchers.eq(999L));
    }
}
