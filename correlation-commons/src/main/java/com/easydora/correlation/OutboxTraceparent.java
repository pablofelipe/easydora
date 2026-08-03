package com.easydora.correlation;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import java.util.HashMap;
import java.util.Map;

/**
 * Captures/restores a W3C traceparent across the Outbox's write-now
 * -publish-later gap, the same gap {@link CorrelationContext}/{@link
 * OutboxEnvelope} already cross for correlationId/messageId (see
 * docs/adr/0024's 2026-08-03 Update).
 *
 * Deliberately built on Micrometer Tracing's own {@link Tracer}/
 * {@link Propagator} abstractions, not raw OpenTelemetry API: Spring
 * AMQP's {@code RabbitTemplate.setObservationEnabled(true)} parents its
 * own producer span from whatever Micrometer considers "current" --
 * {@code Tracer.currentSpan()} -- not from OpenTelemetry's own
 * {@code Context.current()} directly. An earlier version of this class
 * used the raw OTel API and silently failed to connect: every
 * outbox-mediated publish's trace was internally consistent (write's
 * captured span correctly threaded through to the consumer side) but
 * never linked back to the original HTTP request, since making an OTel
 * {@code Context} current does not by itself update Micrometer's own
 * current-span tracking. Both callers (write-time capture, publish-time
 * restore) must be given the {@code Tracer}/{@code Propagator} beans
 * Micrometer Tracing's OTel bridge already auto-configures in every
 * service -- this class takes them as parameters rather than reaching for
 * a global, keeping it consistent with how every other Spring bean in
 * these services gets its own collaborators.
 */
public final class OutboxTraceparent {

    private OutboxTraceparent() {
    }

    /**
     * Captures the current span's W3C traceparent, or null if there is no
     * active span -- an outbox row written outside any traced request/
     * message (most unit tests, for example) is a legitimate,
     * unremarkable state, not an error.
     */
    public static String capture(Tracer tracer, Propagator propagator) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(currentSpan.context(), carrier, Map::put);
        String value = carrier.get("traceparent");
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * Restores a previously-captured traceparent (or null) and starts a
     * new PRODUCER span as its child -- callers must make it current
     * ({@code tracer.withSpan(span)}) around the actual publish call, and
     * end it afterwards, so the producer span RabbitTemplate's own
     * Observation instrumentation creates is parented under the original
     * request's trace instead of starting an orphan one. A null/blank
     * traceparent starts a new root span instead of throwing -- the same
     * "unremarkable, not an error" handling capture() itself gives it.
     */
    public static Span restoreAndStartProducerSpan(
            Tracer tracer, Propagator propagator, String traceparent, String spanName) {
        Span.Builder builder = (traceparent == null || traceparent.isBlank())
                ? tracer.spanBuilder().setNoParent()
                : propagator.extract(Map.of("traceparent", traceparent), Map::get);
        return builder.name(spanName).kind(Span.Kind.PRODUCER).start();
    }
}
