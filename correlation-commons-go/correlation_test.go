package correlation

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewID_ProducesNonEmptyUniqueValues(t *testing.T) {
	first := NewID()
	second := NewID()

	require.NotEmpty(t, first)
	require.NotEmpty(t, second)
	assert.NotEqual(t, first, second)
}

func TestContext_RoundTripsCorrelationRequestAndMessageID(t *testing.T) {
	ctx := context.Background()
	ctx = WithCorrelationID(ctx, "corr-1")
	ctx = WithRequestID(ctx, "req-1")
	ctx = WithMessageID(ctx, "msg-1")

	assert.Equal(t, "corr-1", CorrelationID(ctx))
	assert.Equal(t, "req-1", RequestID(ctx))
	assert.Equal(t, "msg-1", MessageID(ctx))
}

func TestContext_EmptyWhenNeverSet(t *testing.T) {
	ctx := context.Background()

	assert.Equal(t, "", CorrelationID(ctx))
	assert.Equal(t, "", RequestID(ctx))
	assert.Equal(t, "", MessageID(ctx))
}

func TestCurrentOrNewCorrelationID_ReusesWhatsInContext(t *testing.T) {
	ctx := WithCorrelationID(context.Background(), "existing-corr")

	assert.Equal(t, "existing-corr", CurrentOrNewCorrelationID(ctx))
}

func TestCurrentOrNewCorrelationID_GeneratesOneWhenAbsent(t *testing.T) {
	got := CurrentOrNewCorrelationID(context.Background())

	assert.NotEmpty(t, got)
}

func TestMiddleware_GeneratesCorrelationAndRequestIdWhenClientSendsNone(t *testing.T) {
	var correlationSeenInHandler, requestSeenInHandler string
	handler := Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationSeenInHandler = CorrelationID(r.Context())
		requestSeenInHandler = RequestID(r.Context())
	}))

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	require.NotEmpty(t, correlationSeenInHandler)
	require.NotEmpty(t, requestSeenInHandler)
	assert.Equal(t, correlationSeenInHandler, rec.Header().Get(CorrelationIDHeader))
	assert.Equal(t, requestSeenInHandler, rec.Header().Get(RequestIDHeader))
}

func TestMiddleware_ReusesClientSuppliedCorrelationId(t *testing.T) {
	handler := Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set(CorrelationIDHeader, "client-supplied-id")
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	assert.Equal(t, "client-supplied-id", rec.Header().Get(CorrelationIDHeader))
}

func TestMiddleware_GeneratesADifferentRequestIdEveryTime(t *testing.T) {
	handler := Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))

	req1 := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec1 := httptest.NewRecorder()
	handler.ServeHTTP(rec1, req1)

	req2 := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec2 := httptest.NewRecorder()
	handler.ServeHTTP(rec2, req2)

	assert.NotEqual(t, rec1.Header().Get(RequestIDHeader), rec2.Header().Get(RequestIDHeader))
}
