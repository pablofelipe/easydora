package com.easydora.authservice.config;

import com.easydora.authservice.controller.UserQueryController;
import com.easydora.authservice.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * auth-service has no protected endpoint of its own (it produces the
 * cross-service JWT broadcast, it doesn't consume it), so anyRequest() is
 * denyAll() rather than authenticated() -- this proves any path outside the
 * permitAll list is rejected outright, with no dependency on an
 * authentication mechanism this service doesn't actually have.
 */
@WebMvcTest(UserQueryController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @Test
    void rejectsAnyPathOutsideThePermitAllList() throws Exception {
        mockMvc.perform(get("/some-endpoint-that-does-not-exist"))
                .andExpect(status().isForbidden());
    }
}
