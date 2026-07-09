package com.easydora.correlation;

/**
 * Shared names for the identifiers used to trace one business operation
 * across services: HTTP headers on the way in/out, and the SLF4J MDC keys
 * that make them show up in every log line for the duration of a request
 * or a RabbitMQ message being handled.
 */
public final class CorrelationConstants {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String MESSAGE_ID_MDC_KEY = "messageId";

    private CorrelationConstants() {
    }
}
