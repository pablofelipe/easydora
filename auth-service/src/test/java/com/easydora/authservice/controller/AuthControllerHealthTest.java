package com.easydora.authservice.controller;

import com.easydora.authservice.config.SecurityConfig;
import com.easydora.authservice.service.AuthService;
import com.easydora.authservice.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /health used to hardcode "database": "Connected" with no real check
 * (ADR-0010's residual gap) -- this is the endpoint Docker's own
 * HEALTHCHECK and the Gateway route hit. It now performs a real,
 * short-timeout connectivity probe via the injected DataSource.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerHealthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private AuthService authService;

    @MockBean
    private DataSource dataSource;

    @Test
    void healthReportsConnectedAndOkWhenDatabaseIsReachable() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);

        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.database").value("Connected"));
    }

    @Test
    void healthReportsDisconnectedAndServiceUnavailableWhenDatabaseIsUnreachable() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        mockMvc.perform(get("/health"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.database").value("Disconnected"))
            .andExpect(jsonPath("$.status").value("DOWN"));
    }
}
