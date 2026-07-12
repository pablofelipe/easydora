package com.easydora.orders.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A concurrent-write conflict (ADR-0033's @Version) must map to 409
 * Conflict specifically -- not fall through to the generic
 * RuntimeException handler's 400 Bad Request, which would make a lost-
 * update-prevention signal indistinguishable from an ordinary business
 * rejection.
 */
class GlobalExceptionHandlerTest {

    @Test
    void aConcurrencyConflictMapsToConflictNotBadRequest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Map<String, Object>> response =
                handler.handleOptimisticLockingFailure(new OptimisticLockingFailureException("stale row"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
