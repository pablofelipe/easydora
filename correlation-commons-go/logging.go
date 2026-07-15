package correlation

import (
	"context"
	"io"
	"log/slog"
)

// Logger wraps *slog.Logger with the service name to embed on every line
// it produces -- required because this package is shared across every Go
// service in this project (inventory-service, api-gateway), so the service
// name can no longer be a hardcoded constant.
type Logger struct {
	slog    *slog.Logger
	service string
}

// NewLogger builds a logfmt (key=value) structured logger, the same
// format used by the Spring services' logging.pattern.console (see
// docs/architecture/observability.md) -- no JSON, no external dependency,
// just the stdlib's log/slog. serviceName is embedded on every line this
// Logger produces.
func NewLogger(w io.Writer, serviceName string) *Logger {
	return &Logger{slog: slog.New(slog.NewTextHandler(w, nil)), service: serviceName}
}

// Info logs one structured line carrying the current CorrelationId/
// RequestId/MessageId from ctx (blank if unset) plus whatever extra
// key/value pairs the caller supplies (typically "event" and
// "aggregateId" at a domain-event boundary).
func Info(logger *Logger, ctx context.Context, msg string, kv ...any) {
	args := []any{
		"service", logger.service,
		"correlationId", CorrelationID(ctx),
		"requestId", RequestID(ctx),
		"messageId", MessageID(ctx),
	}
	args = append(args, kv...)
	logger.slog.Info(msg, args...)
}

// Error is Info's error-level counterpart -- same fields, same shape, so a
// failure at a domain-event boundary is never harder to correlate than its
// success would have been.
func Error(logger *Logger, ctx context.Context, msg string, kv ...any) {
	args := []any{
		"service", logger.service,
		"correlationId", CorrelationID(ctx),
		"requestId", RequestID(ctx),
		"messageId", MessageID(ctx),
	}
	args = append(args, kv...)
	logger.slog.Error(msg, args...)
}
