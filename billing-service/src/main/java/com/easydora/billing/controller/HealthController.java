package com.easydora.billing.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // A short-timeout, real connectivity probe (see ADR-0010's residual
    // gap: this endpoint -- the one Docker's own HEALTHCHECK and the
    // Gateway route hit -- used to hardcode a claim about the database
    // without ever checking it). 2s is generous against this project's own
    // measured healthy-backend latencies (100-115ms, ADR-0006) while still
    // bounding how long a caller waits on a genuinely stuck connection.
    private boolean isDatabaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean databaseReachable = isDatabaseReachable();

        Map<String, Object> response = new HashMap<>();
        response.put("status", databaseReachable ? "OK" : "DOWN");
        response.put("service", "billing-service");
        response.put("port", "8085");
        response.put("database", databaseReachable ? "Connected" : "Disconnected");

        return databaseReachable
            ? ResponseEntity.ok(response)
            : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        Map<String, String> response = new HashMap<>();
        response.put("message", "pong from billing service");
        response.put("port", "8085");
        return ResponseEntity.ok(response);
    }
}
