package com.easydora.products.controller;

import com.easydora.products.config.JwtAuthenticationFilter.JwtUserInfo;
import com.easydora.products.dto.ProductRequest;
import com.easydora.products.dto.ProductResponse;
import com.easydora.products.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that seller identity used for business decisions comes exclusively
 * from the authenticated JWT principal, never from the client-supplied
 * X-User-Id header. Each test authenticates as seller "real-seller-111" and
 * sends a divergent X-User-Id ("spoofed-seller-999") to demonstrate the
 * header is inert.
 */
@WebMvcTest(ProductController.class)
class ProductControllerAuthenticationTest {

    private static final Long REAL_SELLER_USER_ID = 111L;
    private static final String REAL_SELLER_ID = "111";
    private static final String SPOOFED_HEADER_SELLER_ID = "999";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductService productService;

    @MockBean
    private DataSource dataSource;

    private Authentication authenticationFor(Long userId) {
        JwtUserInfo principal = new JwtUserInfo(userId, "seller@example.com", "Real", "Seller", "SELLER");
        return new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_SELLER")));
    }

    @Test
    void getMyProducts_usesAuthenticatedPrincipal_ignoringDivergentHeader() throws Exception {
        when(productService.getProductsBySeller(REAL_SELLER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/my-products")
                .header("X-User-Id", SPOOFED_HEADER_SELLER_ID)
                .with(authentication(authenticationFor(REAL_SELLER_USER_ID))))
            .andExpect(status().isOk());

        verify(productService).getProductsBySeller(REAL_SELLER_ID);
        verify(productService, never()).getProductsBySeller(SPOOFED_HEADER_SELLER_ID);
    }

    @Test
    void createProduct_usesAuthenticatedPrincipal_ignoringDivergentHeader() throws Exception {
        ProductRequest request = new ProductRequest();
        request.setName("Widget");
        request.setPrice(new BigDecimal("10.00"));
        ProductResponse response = new ProductResponse();
        response.setId("product-1");
        when(productService.createProduct(any(ProductRequest.class), eq(REAL_SELLER_ID))).thenReturn(response);

        mockMvc.perform(post("/createProduct")
                .header("X-User-Id", SPOOFED_HEADER_SELLER_ID)
                .with(authentication(authenticationFor(REAL_SELLER_USER_ID)))
                .with(csrf())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk());

        verify(productService).createProduct(any(ProductRequest.class), eq(REAL_SELLER_ID));
        verify(productService, never()).createProduct(any(ProductRequest.class), eq(SPOOFED_HEADER_SELLER_ID));
    }

    @Test
    void updateProduct_usesAuthenticatedPrincipal_ignoringDivergentHeader() throws Exception {
        ProductRequest request = new ProductRequest();
        request.setName("Widget");
        request.setPrice(new BigDecimal("10.00"));
        ProductResponse response = new ProductResponse();
        response.setId("product-1");
        when(productService.updateProduct(eq("product-1"), any(ProductRequest.class), eq(REAL_SELLER_ID)))
            .thenReturn(response);

        mockMvc.perform(put("/product-1")
                .header("X-User-Id", SPOOFED_HEADER_SELLER_ID)
                .with(authentication(authenticationFor(REAL_SELLER_USER_ID)))
                .with(csrf())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk());

        verify(productService).updateProduct(eq("product-1"), any(ProductRequest.class), eq(REAL_SELLER_ID));
        verify(productService, never())
            .updateProduct(eq("product-1"), any(ProductRequest.class), eq(SPOOFED_HEADER_SELLER_ID));
    }

    @Test
    void deleteProduct_usesAuthenticatedPrincipal_ignoringDivergentHeader() throws Exception {
        mockMvc.perform(delete("/product-1")
                .header("X-User-Id", SPOOFED_HEADER_SELLER_ID)
                .with(authentication(authenticationFor(REAL_SELLER_USER_ID)))
                .with(csrf()))
            .andExpect(status().isNoContent());

        verify(productService).deleteProduct("product-1", REAL_SELLER_ID);
        verify(productService, never()).deleteProduct("product-1", SPOOFED_HEADER_SELLER_ID);
    }
}
