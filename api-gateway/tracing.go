package main

import (
	"context"
	"log"
	"os"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
)

// setupTracing wires the OpenTelemetry SDK to export spans over OTLP/HTTP
// to the Jaeger container declared in docker-compose.yml (see ADR-0024's
// 2026-08-02 Update). This is additive to the existing CorrelationId
// propagation (correlation-commons-go): CorrelationId stays the grep-able
// log identifier, this trace/span pair adds the visual waterfall and
// per-hop latency ADR-0024 originally rejected. Returns a shutdown func
// the caller must invoke on exit to flush any buffered spans.
func setupTracing(ctx context.Context, serviceName string) func(context.Context) error {
	endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if endpoint == "" {
		// No collector configured (e.g. local `go run` outside Compose) --
		// leave the global no-op tracer in place rather than fail startup.
		return func(context.Context) error { return nil }
	}

	exporter, err := otlptracehttp.New(ctx, otlptracehttp.WithEndpointURL(endpoint))
	if err != nil {
		log.Printf("otel: failed to create OTLP exporter, tracing disabled: %v", err)
		return func(context.Context) error { return nil }
	}

	res, err := resource.New(ctx,
		resource.WithAttributes(semconv.ServiceName(serviceName)),
	)
	if err != nil {
		log.Printf("otel: failed to build resource, using default: %v", err)
		res = resource.Default()
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.TraceContext{})

	log.Printf("otel: tracing enabled, exporting to %s", endpoint)

	return func(shutdownCtx context.Context) error {
		ctx, cancel := context.WithTimeout(shutdownCtx, 5*time.Second)
		defer cancel()
		return tp.Shutdown(ctx)
	}
}
