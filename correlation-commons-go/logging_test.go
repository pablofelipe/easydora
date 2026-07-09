package correlation

import (
	"bytes"
	"context"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestLog_IncludesCorrelationRequestAndMessageIdFromContext(t *testing.T) {
	var buf bytes.Buffer
	logger := NewLogger(&buf, "test-service")

	ctx := WithCorrelationID(context.Background(), "corr-1")
	ctx = WithRequestID(ctx, "req-1")
	ctx = WithMessageID(ctx, "msg-1")

	Info(logger, ctx, "stock reserved", "event", "stock.reserved", "aggregateId", "order-42")

	out := buf.String()
	assert.Contains(t, out, "service=test-service")
	assert.Contains(t, out, "correlationId=corr-1")
	assert.Contains(t, out, "requestId=req-1")
	assert.Contains(t, out, "messageId=msg-1")
	assert.Contains(t, out, "event=stock.reserved")
	assert.Contains(t, out, "aggregateId=order-42")
	assert.Contains(t, out, "msg=\"stock reserved\"")
}

func TestLog_OmitsMissingIdsGracefully(t *testing.T) {
	var buf bytes.Buffer
	logger := NewLogger(&buf, "test-service")

	Info(logger, context.Background(), "startup", "event", "server.started")

	out := buf.String()
	assert.True(t, strings.Contains(out, "correlationId=\"\"") || strings.Contains(out, "correlationId= "),
		"expected an empty correlationId field, got: %s", out)
}

func TestLog_UsesTheServiceNameGivenToNewLogger(t *testing.T) {
	var buf bytes.Buffer
	logger := NewLogger(&buf, "another-service")

	Info(logger, context.Background(), "hello", "event", "x")

	assert.Contains(t, buf.String(), "service=another-service")
}
