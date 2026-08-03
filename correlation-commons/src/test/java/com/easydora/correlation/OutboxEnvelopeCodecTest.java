package com.easydora.correlation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The envelope is an Outbox-internal implementation detail only: it lets
 * an OutboxPublisher promote correlationId/messageId to native AMQP
 * properties at actual publish time without a new database column, and
 * without changing the wire shape of the event body itself once
 * published. It must round-trip a bare, non-JSON-object body (e.g.
 * auth-service's user.verified raw numeric string "888") byte-for-byte,
 * not just a JSON-object-shaped one.
 */
class OutboxEnvelopeCodecTest {

    @Test
    void roundTripsAJsonObjectBody() {
        String stored = OutboxEnvelopeCodec.wrap("corr-1", "msg-1", "00-trace1-span1-01", "{\"userId\":42}");

        OutboxEnvelope envelope = OutboxEnvelopeCodec.unwrap(stored);

        assertThat(envelope.correlationId()).isEqualTo("corr-1");
        assertThat(envelope.messageId()).isEqualTo("msg-1");
        assertThat(envelope.traceparent()).isEqualTo("00-trace1-span1-01");
        assertThat(envelope.body()).isEqualTo("{\"userId\":42}");
    }

    @Test
    void roundTripsABareNonJsonObjectBodyExactly() {
        String stored = OutboxEnvelopeCodec.wrap("corr-2", "msg-2", "00-trace2-span2-01", "888");

        OutboxEnvelope envelope = OutboxEnvelopeCodec.unwrap(stored);

        assertThat(envelope.body()).isEqualTo("888");
    }

    @Test
    void toleratesNoActiveTraceAtWriteTime() {
        // No active span is a legitimate, unremarkable state (e.g. an
        // event written outside any traced request/message), not an error
        // -- must round-trip as null, not throw or produce a bogus value.
        String stored = OutboxEnvelopeCodec.wrap("corr-3", "msg-3", null, "888");

        OutboxEnvelope envelope = OutboxEnvelopeCodec.unwrap(stored);

        assertThat(envelope.traceparent()).isNull();
    }
}
