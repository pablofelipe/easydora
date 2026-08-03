package com.easydora.correlation;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds a real Micrometer Tracing OTel bridge (OtelTracer/OtelPropagator
 * over a self-contained OpenTelemetry SDK instance, no Jaeger/OTLP export)
 * rather than mocking Tracer/Propagator -- this is the exact abstraction
 * layer capture()/restoreAndStartProducerSpan() are built on, and the one
 * an earlier, raw-OTel-API version of this class got wrong (see the class
 * Javadoc): only a real Tracer/Propagator round-trip can actually prove
 * parent/child linkage the way Spring AMQP's Observation-based
 * instrumentation will consult it.
 */
class OutboxTraceparentTest {

    private Tracer tracer;
    private Propagator propagator;

    @BeforeEach
    void buildARealOtelBackedTracerAndPropagator() {
        OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().setResource(Resource.getDefault()).build();
        ContextPropagators contextPropagators = ContextPropagators.create(W3CTraceContextPropagator.getInstance());
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(contextPropagators)
                .build();
        io.opentelemetry.api.trace.Tracer otelTracer = openTelemetry.getTracer("OutboxTraceparentTest");

        tracer = new OtelTracer(otelTracer, currentTraceContext, event -> { });
        propagator = new OtelPropagator(contextPropagators, otelTracer);
    }

    @Test
    void captureReturnsNullWhenNoSpanIsCurrent() {
        assertThat(OutboxTraceparent.capture(tracer, propagator)).isNull();
    }

    @Test
    void capturedTraceparentThreadsThroughARestoredProducerSpanAsItsChild() {
        Span originalSpan = tracer.spanBuilder().name("original request").start();
        String captured;
        try (Tracer.SpanInScope scope = tracer.withSpan(originalSpan)) {
            captured = OutboxTraceparent.capture(tracer, propagator);
        } finally {
            originalSpan.end();
        }

        assertThat(captured).isNotNull();

        Span restoredSpan = OutboxTraceparent.restoreAndStartProducerSpan(
                tracer, propagator, captured, "outbox publish");
        try {
            assertThat(restoredSpan.context().traceId()).isEqualTo(originalSpan.context().traceId());
            assertThat(restoredSpan.context().parentId()).isEqualTo(originalSpan.context().spanId());
        } finally {
            restoredSpan.end();
        }
    }

    @Test
    void restoreOfNullStartsANewRootSpanInsteadOfThrowing() {
        Span span = OutboxTraceparent.restoreAndStartProducerSpan(tracer, propagator, null, "outbox publish");
        try {
            assertThat(span.isNoop()).isFalse();
        } finally {
            span.end();
        }
    }
}
