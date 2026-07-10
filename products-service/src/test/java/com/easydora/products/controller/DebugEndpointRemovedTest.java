package com.easydora.products.controller;

import com.easydora.products.config.JwtAuthenticationFilter;
import com.easydora.products.config.SecurityConfig;
import com.easydora.products.repository.SellerRepository;
import com.easydora.products.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the production debug surface (GET /debug/tokens, which made
 * JwtAuthenticationFilter log every cached user's email -- an anonymous way
 * to enumerate active sessions) is no longer reachable anonymously: the
 * controller is deleted and "/debug/**" is no longer in SecurityConfig's
 * permitAll list, so an anonymous request is rejected by
 * AuthorizationFilter (403) before Spring MVC's dispatcher would even get a
 * chance to report "no handler" -- security runs first in this filter
 * chain, exactly the same ordering already relied on elsewhere in this
 * project. Plain @WebMvcTest (no controller argument) auto-detects every
 * real @RestController in the application, so this reflects the actual
 * controller set rather than an artificially narrowed slice.
 */
@WebMvcTest
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DebugEndpointRemovedTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @MockBean
    private SellerRepository sellerRepository;

    @Test
    void debugTokensEndpointIsNotReachableAnonymously() throws Exception {
        mockMvc.perform(get("/debug/tokens")).andExpect(status().isForbidden());
    }

    @Test
    void debugHealthEndpointIsNotReachableAnonymously() throws Exception {
        mockMvc.perform(get("/debug/health")).andExpect(status().isForbidden());
    }
}
