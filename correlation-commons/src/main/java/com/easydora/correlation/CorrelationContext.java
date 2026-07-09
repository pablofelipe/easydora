package com.easydora.correlation;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Single point of contact for reading/generating the identifiers that
 * trace a business operation. Nothing outside this package should call
 * MDC or UUID directly for correlation/request/message ids -- keeping
 * generation and lookup in one place is what keeps this from being
 * re-implemented slightly differently at every publish/consume call site.
 */
public final class CorrelationContext {

    private CorrelationContext() {
    }

    /** The current request/message's CorrelationId, or a freshly generated
     * one if none is set (e.g. code running outside a request/message
     * context, such as a unit test) -- callers should never publish an
     * event with no CorrelationId at all. */
    public static String currentOrNewCorrelationId() {
        String existing = MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);
        return (existing != null && !existing.isBlank()) ? existing : UUID.randomUUID().toString();
    }

    public static String newMessageId() {
        return UUID.randomUUID().toString();
    }

    public static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }
}
