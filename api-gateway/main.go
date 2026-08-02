package main

import (
	"context"
	"easydora/correlation-commons"
	"encoding/json"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/sony/gobreaker"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strconv"
	"sync"
	"time"
)

var gatewayLogger = correlation.NewLogger(os.Stdout, "api-gateway")

// HTTP request rate/latency/errors: promhttp.Handler() alone only exposes Go
// runtime metrics, not request-level ones (unlike Micrometer's automatic
// http_server_requests_seconds in the four Spring services) -- this is the
// small amount of custom instrumentation that gap actually requires. Route
// label uses c.FullPath() (the matched route template, e.g. "/auth/*proxyPath"),
// never the raw request path, to keep cardinality bounded regardless of
// what a client sends. See ADR-0036.
var (
	httpRequestsTotal = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "Total HTTP requests handled by the gateway, by route, method and status.",
		},
		[]string{"method", "route", "status"},
	)
	httpRequestDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "HTTP request duration in seconds, by route and method.",
			Buckets: prometheus.DefBuckets,
		},
		[]string{"method", "route"},
	)
)

func metricsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()

		route := c.FullPath()
		if route == "" {
			route = "unmatched"
		}
		status := strconv.Itoa(c.Writer.Status())

		httpRequestsTotal.WithLabelValues(c.Request.Method, route, status).Inc()
		httpRequestDuration.WithLabelValues(c.Request.Method, route).Observe(time.Since(start).Seconds())
	}
}

// correlationMiddleware is the birthplace of a business operation's
// CorrelationId at the edge: reused from the X-Correlation-Id request
// header if the client already sent one, generated otherwise. RequestId is
// always freshly generated, once per request. Both are put on the request
// context (for this gateway's own logging) and, critically, also set
// directly on c.Request.Header so the reverse proxy -- which forwards
// c.Request.Header verbatim via its default Director -- carries the
// CorrelationId through to whichever downstream service handles the
// proxied request.
func correlationMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		incoming := c.GetHeader(correlation.CorrelationIDHeader)
		correlationID := incoming
		if correlationID == "" {
			correlationID = correlation.NewID()
		}
		requestID := correlation.NewID()

		c.Request.Header.Set(correlation.CorrelationIDHeader, correlationID)

		ctx := correlation.WithCorrelationID(c.Request.Context(), correlationID)
		ctx = correlation.WithRequestID(ctx, requestID)
		c.Request = c.Request.WithContext(ctx)

		c.Header(correlation.CorrelationIDHeader, correlationID)
		c.Header(correlation.RequestIDHeader, requestID)

		c.Next()
	}
}

// responseHeaderTimeout bounds how long the proxy waits for a downstream's
// response headers before treating the call as a transport failure (see
// transportFailureContextKey). This was 30s, given directly with no
// measurement behind it (ADR-0006's own documented residual gap). Measured
// against a real container frozen mid-request (docker pause, holding an
// open TCP connection but never answering -- distinct from a fast
// connection-refused failure, which ADR-0006's original live verification
// already covered): at 30s, 5 consecutive failures (the breaker's own
// ReadyToTrip threshold) left the Gateway exposed for 150s before it
// started short-circuiting. 5s keeps that worst case at 25s -- still well
// above every healthy-backend latency this project has ever measured
// (100-115ms per ADR-0006's live verification) -- while bounding the
// slow-failure exposure to roughly the same order of magnitude as the
// breaker's own 30s cooldown. See docs/adr/0006-gateway-circuit-breaker.md's
// Update for the full measurement.
const responseHeaderTimeout = 5 * time.Second

// circuitBreakerSettings applies the same thresholds to every service:
// 5 consecutive downstream-unreachable failures opens the breaker, and it
// stays open for 30s before allowing a single trial request through
// (half-open). See docs/adr/0006-gateway-circuit-breaker.md for why these
// values and not something else.
func circuitBreakerSettings(serviceName string) gobreaker.Settings {
	return gobreaker.Settings{
		Name: serviceName,
		Timeout: 30 * time.Second,
		ReadyToTrip: func(counts gobreaker.Counts) bool {
			return counts.ConsecutiveFailures >= 5
		},
	}
}

// Service configuration
type ServiceConfig struct {
	URL         string
	Name        string
	Implemented bool
}

var (
	services = map[string]ServiceConfig{
		"auth": {
			URL:         getEnv("AUTH_SERVICE_URL", "http://auth-service:8081"),
			Name:        "auth-service",
			Implemented: true,
		},
		"products": {
			URL:         getEnv("PRODUCTS_SERVICE_URL", "http://products-service:8082"),
			Name:        "products-service",
			Implemented: true,
		},
		"inventory": {
			URL:         getEnv("INVENTORY_SERVICE_URL", "http://inventory-service:8083"),
			Name:        "inventory-service", 
			Implemented: true,
		},
		"orders": {
			URL:         getEnv("ORDERS_SERVICE_URL", "http://orders-service:8084"),
			Name:        "orders-service",
			Implemented: true,
		},
		"billing": {
			URL:         getEnv("BILLING_SERVICE_URL", "http://billing-service:8085"),
			Name:        "billing-service",
			Implemented: true,
		},
		"notification": {
			URL:         getEnv("NOTIFICATION_SERVICE_URL", "http://notification-service:8086"),
			Name:        "notification-service",
			Implemented: true,
		},
	}
)

func main() {
	ctx := context.Background()
	shutdownTracing := setupTracing(ctx, getEnv("OTEL_SERVICE_NAME", "api-gateway"))
	defer func() {
		stopCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = shutdownTracing(stopCtx)
	}()

	router := gin.Default()
	router.Use(correlationMiddleware())
	router.Use(metricsMiddleware())

	// Health check endpoints
	router.GET("/health", healthCheck)
	router.GET("/ping", ping)

	// Prometheus scrape endpoint (see ADR-0036). promhttp.Handler() serves
	// the default registry, which already includes Go runtime metrics
	// (goroutines, heap) and process metrics with zero custom collectors.
	router.GET("/metrics", gin.WrapH(promhttp.Handler()))

	setupServiceRoutes(router)

	port := getEnv("PORT", "8080")
	log.Printf("API Gateway starting on port %s", port)

	// otelhttp.NewHandler wraps the whole router: it starts a server span
	// per incoming request, extracting an incoming W3C traceparent header
	// if present (otelhttp uses the propagator set by setupTracing) --
	// this is how a trace started by the frontend or curl continues
	// through the Gateway rather than starting a disconnected new one.
	if err := http.ListenAndServe(":"+port, otelhttp.NewHandler(router, "api-gateway")); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}

func setupServiceRoutes(router *gin.Engine) {
	for path, config := range services {
		serviceGroup := router.Group("/" + path)
		
		if config.Implemented && config.URL != "" {
			// Service implemented - use reverse proxy with a per-service
			// circuit breaker (see ADR-0006, extended to billing by ADR-0009).
			serviceGroup.Any("/*proxyPath", createReverseProxyWithBreaker(config.URL, config.Name))
			log.Printf("%s proxy configured: %s", config.Name, config.URL)
		} else {
			// Service not implemented - use mock
			serviceGroup.Any("/*proxyPath", createMockHandler(config.Name))
			log.Printf("%s not implemented - using mock responses", config.Name)
		}
	}
}

// Generic handler for services that are not yet implemented
func createMockHandler(serviceName string) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.JSON(503, gin.H{
			"error":      "Service temporarily unavailable",
			"service":    serviceName,
			"status":     "not_implemented",
			"message":    "Service is not yet implemented",
			"timestamp":  time.Now().Format(time.RFC3339),
			"path":       c.Request.URL.Path,
			"method":     c.Request.Method,
		})
	}
}

// Reverse proxy for implemented services
func createReverseProxy(target, serviceName string) gin.HandlerFunc {
	return func(c *gin.Context) {
		targetURL, err := url.Parse(target)
		if err != nil {
			log.Printf("Error parsing target URL %s: %v", target, err)
			c.JSON(500, gin.H{
				"error":   "Invalid service configuration",
				"service": serviceName,
				"target":  target,
			})
			return
		}

		proxy := httputil.NewSingleHostReverseProxy(targetURL)

		// Configure error handler for debugging. Also marks this request as a
		// genuine transport failure (see transportFailureContextKey) --
		// createReverseProxyWithBreaker reads that flag instead of the
		// response status code, so a backend that legitimately answers with
		// its own 502 is never confused with the downstream being
		// unreachable.
		proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
			log.Printf("Proxy error for %s: %v", serviceName, err)
			c.Set(transportFailureContextKey, true)
			w.WriteHeader(http.StatusBadGateway)
			json.NewEncoder(w).Encode(gin.H{
				"error":   "Service unavailable",
				"service": serviceName,
				"details": err.Error(),
			})
		}

		// otelhttp.NewTransport wraps the outbound call: it starts a client
		// span as a child of the inbound server span (propagated via
		// c.Request.Context(), which httputil.ReverseProxy passes through
		// unchanged) and injects the resulting traceparent header onto the
		// outgoing request -- this is how the trace continues into
		// whichever downstream service handles it next.
		proxy.Transport = otelhttp.NewTransport(&http.Transport{
			ResponseHeaderTimeout: responseHeaderTimeout,
			DialContext: (&net.Dialer{
				Timeout:   10 * time.Second,
				KeepAlive: 30 * time.Second,
			}).DialContext,
		})

		// httputil.ReverseProxy copies every backend response header onto
		// the client response via Header.Add, not Set -- since
		// correlationMiddleware already wrote this Gin response's own
		// X-Correlation-Id/X-Request-Id before this handler ever runs, the
		// downstream service's own copies of those same header names would
		// otherwise be appended alongside them, producing a single header
		// with two comma-joined values. curl prints duplicate header lines
		// (easy to miss); a browser's fetch().headers.get() joins them into
		// one confusing "id, id" string. The client's actual request/
		// response pair is with the Gateway, so the Gateway's own hop
		// values -- already correct (CorrelationId reused, RequestId fresh)
		// -- are what should reach the client; the downstream's copies are
		// an internal hop detail that belongs in its own logs only.
		proxy.ModifyResponse = func(res *http.Response) error {
			res.Header.Del(correlation.CorrelationIDHeader)
			res.Header.Del(correlation.RequestIDHeader)
			return nil
		}

		originalPath := c.Request.URL.Path

		correlation.Info(gatewayLogger, c.Request.Context(), "proxying request",
			"event", "gateway.proxy", "aggregateId", originalPath,
			"method", c.Request.Method, "target", targetURL.Host+originalPath)

		c.Request.URL.Scheme = targetURL.Scheme
		c.Request.URL.Host = targetURL.Host
		// The Gateway is a transparent routing layer (ADR-0025): the
		// incoming path -- including the /auth, /products, /orders,
		// /billing, /inventory, /notification segment -- is forwarded
		// byte-for-byte. Every service is expected to expose that same
		// segment itself, so no rewrite happens here.
		c.Request.Host = targetURL.Host

		// Headers for tracing
		c.Request.Header.Set("X-Forwarded-Host", c.Request.Host)
		c.Request.Header.Set("X-Origin-Service", serviceName)
		c.Request.Header.Set("X-Gateway-Service", "api-gateway")

		proxy.ServeHTTP(c.Writer, c.Request)
	}
}

// transportFailureContextKey flags a request as a genuine transport-level
// failure (connection refused, dial timeout, broken pipe) -- set only by
// createReverseProxy's own proxy.ErrorHandler, never inferred from the
// response status code. This is what lets createReverseProxyWithBreaker
// tell "downstream unreachable" apart from "downstream is reachable and
// legitimately answered 502 itself" -- the two were indistinguishable
// under the previous status-code-only check (ADR-0006's own documented
// residual gap).
const transportFailureContextKey = "gatewayTransportFailure"

// createReverseProxyWithBreaker wraps createReverseProxy with a per-service
// gobreaker.CircuitBreaker (see circuitBreakerSettings: 5 consecutive
// failures to open, 30s cooldown before a half-open trial). Every
// implemented entry in setupServiceRoutes uses this wrapper (billing
// included, since ADR-0009) — createReverseProxy itself stays as a plain,
// breaker-less helper used only internally, not routed to directly.
//
// Failure is detected via transportFailureContextKey, set only when
// httputil.ReverseProxy's transport fails outright — connection refused,
// dial timeout, broken pipe — never for a valid HTTP response from the
// backend, even a 4xx/5xx one, including a backend-originated 502. So the
// breaker trips on "downstream unreachable", not "downstream returned an
// error status".
// circuitBreakers holds every breaker createReverseProxyWithBreaker has
// ever built, keyed by ServiceConfig.Name -- the real-time signal
// healthCheck reads instead of the static Implemented config flag (see
// ADR-0010's Update, extended to the Go services: /health used to report
// "implemented" unconditionally, never reflecting whether the breaker for
// that service had actually tripped open).
var (
	circuitBreakersMu sync.RWMutex
	circuitBreakers   = make(map[string]*gobreaker.CircuitBreaker)
)

func createReverseProxyWithBreaker(target, serviceName string) gin.HandlerFunc {
	// Built once here and shared across every request this handler serves —
	// createReverseProxyWithBreaker itself only runs once per service, at
	// route-registration time in setupServiceRoutes.
	breaker := gobreaker.NewCircuitBreaker(circuitBreakerSettings(serviceName))
	circuitBreakersMu.Lock()
	circuitBreakers[serviceName] = breaker
	circuitBreakersMu.Unlock()
	plainProxy := createReverseProxy(target, serviceName)

	return func(c *gin.Context) {
		_, err := breaker.Execute(func() (interface{}, error) {
			plainProxy(c)
			if c.GetBool(transportFailureContextKey) {
				return nil, fmt.Errorf("%s unreachable", serviceName)
			}
			return nil, nil
		})

		if err == gobreaker.ErrOpenState || err == gobreaker.ErrTooManyRequests {
			c.JSON(http.StatusServiceUnavailable, gin.H{
				"error":   "Circuit breaker open — service temporarily unavailable",
				"service": serviceName,
			})
		}
	}
}

// breakerStateLabel translates gobreaker's own State into the label
// healthCheck reports -- Closed is the normal, healthy state; Open means
// ReadyToTrip fired and requests are being short-circuited; HalfOpen means
// the cooldown elapsed and a single trial request is being let through.
func breakerStateLabel(state gobreaker.State) string {
	switch state {
	case gobreaker.StateClosed:
		return "healthy"
	case gobreaker.StateOpen:
		return "unavailable"
	case gobreaker.StateHalfOpen:
		return "recovering"
	default:
		return "unknown"
	}
}

func healthCheck(c *gin.Context) {
	// Service status: a real, real-time signal (the service's own circuit
	// breaker state) where one exists, not just the static Implemented
	// config flag -- see circuitBreakers' own doc comment.
	servicesStatus := make(map[string]string)
	for path, config := range services {
		if !config.Implemented || config.URL == "" {
			servicesStatus[path] = "not_implemented"
			continue
		}

		circuitBreakersMu.RLock()
		breaker, ok := circuitBreakers[config.Name]
		circuitBreakersMu.RUnlock()

		if !ok {
			servicesStatus[path] = "implemented"
			continue
		}
		servicesStatus[path] = breakerStateLabel(breaker.State())
	}

	status := gin.H{
		"status":    "OK",
		"service":   "api-gateway",
		"timestamp": time.Now().Format(time.RFC3339),
		"services":  servicesStatus,
	}

	c.JSON(200, status)
}

func ping(c *gin.Context) {
	c.JSON(200, gin.H{
		"message":   "pong from api gateway",
		"service":   "api-gateway",
		"timestamp": time.Now().Format(time.RFC3339),
	})
}

func getEnv(key, defaultValue string) string {
	value := os.Getenv(key)
	if value == "" {
		return defaultValue
	}
	return value
}