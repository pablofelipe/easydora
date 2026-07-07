package com.easydora.authservice.controller;

import com.easydora.authservice.config.SecurityConfig;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.entity.UserRole;
import com.easydora.authservice.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserQueryController.class)
@Import(SecurityConfig.class)
class UserQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @Test
    void returnsNotificationProfileForExistingUser() throws Exception {
        User user = new User("buyer@example.com", "hashed-password", "Casey", "Buyer", UserRole.BUYER);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        mockMvc.perform(get("/users/42/notification-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("buyer@example.com"))
                .andExpect(jsonPath("$.firstName").value("Casey"))
                .andExpect(jsonPath("$.lastName").value("Buyer"));
    }

    @Test
    void returnsNotFoundForUnknownUser() throws Exception {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/users/99/notification-profile"))
                .andExpect(status().isNotFound());
    }
}
