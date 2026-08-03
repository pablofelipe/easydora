package messaging

import (
	"context"

	amqp "github.com/rabbitmq/amqp091-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/trace"
)

// amqpHeaderCarrier adapts amqp.Table to OTel's propagation.TextMapCarrier
// so a W3C traceparent can ride in a message's headers, the same way
// CorrelationId/MessageId already ride in its native correlation_id/
// message_id properties (see docs/architecture/observability.md). A
// separate header, not those native properties, because traceparent is a
// single opaque string with its own W3C-defined format -- reusing
// correlation_id for it would conflate two different identifiers this
// project deliberately keeps distinct.
type amqpHeaderCarrier amqp.Table

func (c amqpHeaderCarrier) Get(key string) string {
	if v, ok := c[key]; ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}

func (c amqpHeaderCarrier) Set(key, value string) {
	c[key] = value
}

func (c amqpHeaderCarrier) Keys() []string {
	keys := make([]string, 0, len(c))
	for k := range c {
		keys = append(keys, k)
	}
	return keys
}

// extractTraceContext reads an incoming delivery's headers for a
// traceparent the publisher injected, continuing that trace rather than
// starting a disconnected one -- the RabbitMQ-hop equivalent of
// contextFromDelivery's CorrelationId reuse, above.
func extractTraceContext(ctx context.Context, headers amqp.Table) context.Context {
	if headers == nil {
		headers = amqp.Table{}
	}
	return otel.GetTextMapPropagator().Extract(ctx, amqpHeaderCarrier(headers))
}

// startConsumerSpan starts a span representing this queue's handling of one
// delivery, as a child of whatever traceparent extractTraceContext found
// (or a new root span if none was present -- e.g. a message published
// before this ADR's Update). name matches runConsumerLoop's own consumer
// name/queue naming for readability in the Jaeger UI.
func startConsumerSpan(ctx context.Context, queueName string) (context.Context, trace.Span) {
	tracer := otel.Tracer("inventory-service/messaging")
	return tracer.Start(ctx, queueName+" receive", trace.WithSpanKind(trace.SpanKindConsumer))
}

// restoreOutboxTraceContext reconstructs a Context from a traceparent
// captured at outbox write time (ADR-0024's 2026-08-03 Update), the
// RabbitMQ-hop equivalent of correlationId/messageId's own envelope trick
// crossing the same write-now-publish-later gap. An empty traceParent
// (no active span at write time) is a legitimate, unremarkable state --
// ctx is returned unchanged in that case, same as extractTraceContext
// does for headers with no traceparent key.
func restoreOutboxTraceContext(ctx context.Context, traceParent string) context.Context {
	if traceParent == "" {
		return ctx
	}
	carrier := amqpHeaderCarrier{"traceparent": traceParent}
	return otel.GetTextMapPropagator().Extract(ctx, carrier)
}

// startOutboxProducerSpan starts a span representing this outbox row's
// actual publish, as a child of whatever restoreOutboxTraceContext
// restored (or a new root span if the row carried no traceparent at all).
func startOutboxProducerSpan(ctx context.Context, routingKey string) (context.Context, trace.Span) {
	tracer := otel.Tracer("inventory-service/messaging")
	return tracer.Start(ctx, routingKey+" outbox publish", trace.WithSpanKind(trace.SpanKindProducer))
}
