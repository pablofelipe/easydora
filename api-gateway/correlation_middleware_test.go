package main

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"easydora/correlation-commons"
	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCorrelationMiddleware_GeneratesCorrelationAndRequestIdWhenClientSendsNone(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(correlationMiddleware())

	var correlationIDSeenByHandler, requestIDSeenByHandler string
	router.GET("/example", func(c *gin.Context) {
		ctx := c.Request.Context()
		correlationIDSeenByHandler = correlation.CorrelationID(ctx)
		requestIDSeenByHandler = correlation.RequestID(ctx)
	})

	req := httptest.NewRequest(http.MethodGet, "/example", nil)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	require.NotEmpty(t, correlationIDSeenByHandler)
	require.NotEmpty(t, requestIDSeenByHandler)
	assert.Equal(t, correlationIDSeenByHandler, rec.Header().Get(correlation.CorrelationIDHeader))
	assert.Equal(t, requestIDSeenByHandler, rec.Header().Get(correlation.RequestIDHeader))
}

func TestCorrelationMiddleware_ReusesClientSuppliedCorrelationIdAndForwardsItDownstream(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(correlationMiddleware())

	var forwardedHeaderSeenByHandler string
	router.GET("/example", func(c *gin.Context) {
		// A reverse proxy forwards whatever is in c.Request.Header, so this
		// is what actually reaches the downstream service -- not just what
		// context.Context carries for local logging.
		forwardedHeaderSeenByHandler = c.Request.Header.Get(correlation.CorrelationIDHeader)
	})

	req := httptest.NewRequest(http.MethodGet, "/example", nil)
	req.Header.Set(correlation.CorrelationIDHeader, "client-supplied-id")
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	assert.Equal(t, "client-supplied-id", forwardedHeaderSeenByHandler)
	assert.Equal(t, "client-supplied-id", rec.Header().Get(correlation.CorrelationIDHeader))
}

func TestCorrelationMiddleware_GeneratedCorrelationIdIsAlsoForwardedDownstream(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(correlationMiddleware())

	var forwardedHeaderSeenByHandler string
	router.GET("/example", func(c *gin.Context) {
		forwardedHeaderSeenByHandler = c.Request.Header.Get(correlation.CorrelationIDHeader)
	})

	req := httptest.NewRequest(http.MethodGet, "/example", nil)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	require.NotEmpty(t, forwardedHeaderSeenByHandler)
	assert.Equal(t, rec.Header().Get(correlation.CorrelationIDHeader), forwardedHeaderSeenByHandler)
}
