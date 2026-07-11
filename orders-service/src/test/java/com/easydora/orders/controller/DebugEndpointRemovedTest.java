package com.easydora.orders.controller;

import com.easydora.orders.config.JwtAuthenticationFilter;
import com.easydora.orders.config.SecurityConfig;
import com.easydora.orders.repository.BuyerRepository;
import com.easydora.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the production debug surface is no longer reachable anonymously --
 * mirroring products-service's own fix for the identical pattern. The real
 * mapped paths are "/debug/debug/tokens" etc, not "/debug/tokens": the
 * controller's class-level @RequestMapping("/debug") combined with each
 * method's own "/debug/..." @GetMapping duplicates the segment. Confirmed
 * live against the running container before this fix -- GET
 * /debug/debug/buyers returned 200 with every real buyer row (PII), and
 * GET /debug/debug/tokens logged every cached user's email. Removal makes
 * the exact path moot either way. Plain @WebMvcTest (no controller
 * argument) auto-detects every real @RestController in the application, so
 * this reflects the actual controller set rather than an artificially
 * narrowed slice.
 */
@WebMvcTest
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DebugEndpointRemovedTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private BuyerRepository buyerRepository;

    @Test
    void debugTokensEndpointIsNotReachableAnonymously() throws Exception {
        mockMvc.perform(get("/debug/debug/tokens")).andExpect(status().isForbidden());
    }

    @Test
    void debugBuyersEndpointIsNotReachableAnonymously() throws Exception {
        mockMvc.perform(get("/debug/debug/buyers")).andExpect(status().isForbidden());
    }

    @Test
    void debugClearTokensEndpointIsNotReachableAnonymously() throws Exception {
        mockMvc.perform(post("/debug/debug/clear-tokens")).andExpect(status().isForbidden());
    }
}
