package com.easydora.correlation;

/**
 * Outbox-internal carrier for correlationId/messageId across the
 * write-now-publish-later gap. Never seen by anything outside the Outbox
 * mechanism itself: OutboxPublisher unwraps it and publishes only
 * {@code body}, promoting correlationId/messageId to native AMQP
 * properties -- the wire shape of the actual event is unchanged.
 */
public record OutboxEnvelope(String correlationId, String messageId, String body) {
}
