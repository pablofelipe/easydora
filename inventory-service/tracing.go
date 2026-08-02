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
// 2026-08-02 Update). Identical shape to api-gateway's own setupTracing --
// duplicated rather than shared, since this is genuinely per-service
// wiring, not the byte-for-byte-identical CorrelationId infra that earns
// correlation-commons-go's exception to "no shared library between
// services" (see docs/architecture/observability.md).
func setupTracing(ctx context.Context, serviceName string) func(context.Context) error {
	endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if endpoint == "" {
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
