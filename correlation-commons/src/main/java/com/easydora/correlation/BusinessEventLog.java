package com.easydora.correlation;

import org.slf4j.Logger;

/**
 * One consistent key=value shape for logging a domain-event boundary
 * (something published, consumed, or otherwise business-significant),
 * so this doesn't get reinvented slightly differently at each call site.
 * CorrelationId/RequestId/MessageId are not passed here -- they are
 * already in MDC for the duration of the request/message and picked up
 * automatically by each service's logging.pattern.console.
 */
public final class BusinessEventLog {

    private BusinessEventLog() {
    }

    public static void info(Logger logger, String event, Object aggregateId, String message) {
        logger.info("event={} aggregateId={} msg={}", event, aggregateId, message);
    }
}
