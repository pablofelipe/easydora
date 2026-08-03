package com.easydora.correlation;

/**
 * Outbox-internal carrier for correlationId/messageId/traceparent across
 * the write-now-publish-later gap. Never seen by anything outside the
 * Outbox mechanism itself: OutboxPublisher unwraps it and publishes only
 * {@code body}, promoting correlationId/messageId to native AMQP
 * properties and traceparent to a producer span parent -- the wire shape
 * of the actual event is unchanged.
 *
 * traceparent may be null: an outbox row written outside any traced
 * request/message (e.g. most unit tests) is a legitimate, unremarkable
 * state, not an error -- see docs/adr/0024's 2026-08-03 Update.
 */
public record OutboxEnvelope(String correlationId, String messageId, String traceparent, String body) {
}
