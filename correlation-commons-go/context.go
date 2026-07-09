package correlation

import "context"

// Header names used both for the inbound/outbound HTTP hop and echoed on
// the response.
const (
	CorrelationIDHeader = "X-Correlation-Id"
	RequestIDHeader     = "X-Request-Id"
)

type contextKey int

const (
	correlationIDKey contextKey = iota
	requestIDKey
	messageIDKey
)

func WithCorrelationID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, correlationIDKey, id)
}

func WithRequestID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, requestIDKey, id)
}

func WithMessageID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, messageIDKey, id)
}

func CorrelationID(ctx context.Context) string {
	return stringOrEmpty(ctx.Value(correlationIDKey))
}

func RequestID(ctx context.Context) string {
	return stringOrEmpty(ctx.Value(requestIDKey))
}

func MessageID(ctx context.Context) string {
	return stringOrEmpty(ctx.Value(messageIDKey))
}

// CurrentOrNewCorrelationID returns the CorrelationId already in ctx, or a
// freshly generated one if none is set -- callers should never publish an
// event or log a business operation with no CorrelationId at all.
func CurrentOrNewCorrelationID(ctx context.Context) string {
	if existing := CorrelationID(ctx); existing != "" {
		return existing
	}
	return NewID()
}

func stringOrEmpty(v any) string {
	s, ok := v.(string)
	if !ok {
		return ""
	}
	return s
}
