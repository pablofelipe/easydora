package main

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

func init() {
	gin.SetMode(gin.TestMode)
}

// newProxyTestServer mounts handler behind a real net/http test server, so
// httputil.ReverseProxy gets a real http.ResponseWriter (it needs
// CloseNotifier support, which a bare httptest.ResponseRecorder doesn't
// implement).
func newProxyTestServer(handler gin.HandlerFunc) *httptest.Server {
	engine := gin.New()
	engine.Any("/*proxyPath", handler)
	return httptest.NewServer(engine)
}

func doRequest(t *testing.T, server *httptest.Server, path string) int {
	t.Helper()
	resp, err := http.Get(server.URL + path)
	if err != nil {
		t.Fatalf("request to %s failed: %v", path, err)
	}
	defer resp.Body.Close()
	return resp.StatusCode
}

// closedPortURL returns a URL guaranteed to refuse every connection:
// a real httptest server is started (to get a free port) then
// immediately closed, so nothing is listening there anymore.
func closedPortURL(t *testing.T) string {
	t.Helper()
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	url := s.URL
	s.Close()
	return url
}

// flakyDownstream accepts TCP connections and counts them, but closes each
// one immediately without writing a valid HTTP response — the proxy's
// transport sees this as a round-trip failure (same failure class as
// connection-refused), while letting the test verify whether the
// downstream was actually dialed.
func flakyDownstream(t *testing.T) (addr string, callCount *int32, closeFn func()) {
	t.Helper()
	var count int32
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to open listener: %v", err)
	}
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			atomic.AddInt32(&count, 1)
			conn.Close()
		}
	}()
	return "http://" + listener.Addr().String(), &count, func() { listener.Close() }
}

// hangingDownstream accepts a TCP connection and then never writes
// anything back -- unlike flakyDownstream (which closes the connection
// immediately, an instant transport-level failure), this simulates a
// downstream that is reachable but frozen mid-request (e.g. `docker
// pause`), the scenario responseHeaderTimeout actually bounds.
func hangingDownstream(t *testing.T) (addr string, closeFn func()) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to open listener: %v", err)
	}
	var mu sync.Mutex
	var conns []net.Conn
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			// Deliberately never read or write -- the connection just sits
			// open until closeFn runs.
			mu.Lock()
			conns = append(conns, conn)
			mu.Unlock()
		}
	}()
	return "http://" + listener.Addr().String(), func() {
		listener.Close()
		mu.Lock()
		defer mu.Unlock()
		for _, c := range conns {
			c.Close()
		}
	}
}

func TestCircuitBreaker_OpensAfterThreshold(t *testing.T) {
	tests := []struct {
		name        string
		serviceName string
	}{
		{"auth", "auth-service"},
		{"products", "products-service"},
		{"inventory", "inventory-service"},
		{"orders", "orders-service"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			downURL := closedPortURL(t)
			handler := createReverseProxyWithBreaker(downURL, tt.serviceName)
			server := newProxyTestServer(handler)
			defer server.Close()

			// 5 consecutive failures: breaker is still closed for each of
			// these, so every one actually reaches (tries to reach) the
			// downstream and gets the proxy's own 502.
			for i := 0; i < 5; i++ {
				code := doRequest(t, server, "/ping")
				if code != http.StatusBadGateway {
					t.Fatalf("call %d: expected 502 from a still-closed breaker, got %d", i+1, code)
				}
			}

			// 6th call: ReadyToTrip fired after the 5th failure, so the
			// breaker is now open and this call must be rejected without
			// reaching the proxy at all.
			code := doRequest(t, server, "/ping")
			if code != http.StatusServiceUnavailable {
				t.Fatalf("expected 503 (breaker open) on the 6th call, got %d", code)
			}
		})
	}
}

func TestCircuitBreaker_PerServiceIndependence(t *testing.T) {
	failingURL := closedPortURL(t)
	healthy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer healthy.Close()

	failingServer := newProxyTestServer(createReverseProxyWithBreaker(failingURL, "inventory-service"))
	defer failingServer.Close()
	healthyServer := newProxyTestServer(createReverseProxyWithBreaker(healthy.URL, "orders-service"))
	defer healthyServer.Close()

	// Trip inventory-service's breaker.
	for i := 0; i < 6; i++ {
		doRequest(t, failingServer, "/ping")
	}
	if code := doRequest(t, failingServer, "/ping"); code != http.StatusServiceUnavailable {
		t.Fatalf("expected inventory-service breaker to be open, got %d", code)
	}

	// orders-service must be completely unaffected.
	if code := doRequest(t, healthyServer, "/ping"); code != http.StatusOK {
		t.Fatalf("expected orders-service unaffected by inventory-service's open breaker, got %d", code)
	}
}

func TestCircuitBreaker_ProxyBypassedWhenOpen(t *testing.T) {
	addr, callCount, closeFn := flakyDownstream(t)
	defer closeFn()

	server := newProxyTestServer(createReverseProxyWithBreaker(addr, "auth-service"))
	defer server.Close()

	for i := 0; i < 5; i++ {
		doRequest(t, server, "/ping")
	}
	countAfterTrip := atomic.LoadInt32(callCount)
	if countAfterTrip < 5 {
		t.Fatalf("expected downstream to have been dialed at least 5 times before the breaker opened, got %d", countAfterTrip)
	}

	// Breaker is now open. Three more calls must not touch the downstream
	// at all — the dial count must not move.
	for i := 0; i < 3; i++ {
		code := doRequest(t, server, "/ping")
		if code != http.StatusServiceUnavailable {
			t.Fatalf("call %d: expected 503 (breaker open), got %d", i+1, code)
		}
	}

	countAfterOpen := atomic.LoadInt32(callCount)
	if countAfterOpen != countAfterTrip {
		t.Fatalf("downstream was dialed again while the breaker is open: before=%d after=%d", countAfterTrip, countAfterOpen)
	}
}

// TestCircuitBreaker_DoesNotTripOnLegitimateBackend502 proves the breaker
// distinguishes "downstream unreachable" from "downstream is reachable and
// legitimately answered 502" -- ADR-0006's own documented residual gap: the
// old detection checked only the proxied response's status code, so a real
// backend intentionally returning 502 (a valid, if unusual, HTTP response)
// was indistinguishable from httputil.ReverseProxy's own transport-failure
// 502 and would incorrectly trip the breaker.
func TestCircuitBreaker_DoesNotTripOnLegitimateBackend502(t *testing.T) {
	var callCount int32
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&callCount, 1)
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer backend.Close()

	server := newProxyTestServer(createReverseProxyWithBreaker(backend.URL, "products-service"))
	defer server.Close()

	for i := 0; i < 6; i++ {
		code := doRequest(t, server, "/ping")
		if code != http.StatusBadGateway {
			t.Fatalf("call %d: expected the backend's own legitimate 502 to pass through untouched, got %d", i+1, code)
		}
	}

	if got := atomic.LoadInt32(&callCount); got != 6 {
		t.Fatalf("expected the breaker to stay closed and keep dialing a reachable backend that legitimately returns 502, got %d dials after 6 requests", got)
	}
}

// TestPlainProxy_DoesNotShortCircuit proves createReverseProxy itself (the
// helper createReverseProxyWithBreaker wraps internally) keeps calling the
// downstream on every request — no breaker, no 503 short-circuit — even
// after repeated failures. This is the "Red" counterpart to the three tests
// above: swap createReverseProxyWithBreaker for createReverseProxy in any of
// them and the 503 assertions stop being true. No service is routed through
// this function directly anymore (see TestSetupServiceRoutes_BillingUsesBreaker) —
// it's exercised here purely as a unit test of the wrapped helper.
func TestPlainProxy_DoesNotShortCircuit(t *testing.T) {
	addr, callCount, closeFn := flakyDownstream(t)
	defer closeFn()

	server := newProxyTestServer(createReverseProxy(addr, "some-service"))
	defer server.Close()

	for i := 0; i < 10; i++ {
		code := doRequest(t, server, "/ping")
		if code != http.StatusBadGateway {
			t.Fatalf("call %d: expected the plain proxy to always attempt the downstream and return 502, got %d", i+1, code)
		}
	}

	if got := atomic.LoadInt32(callCount); got != 10 {
		t.Fatalf("expected the plain proxy to dial the downstream on every one of the 10 calls, got %d dials", got)
	}
}

// TestReverseProxy_TimesOutOnHangingDownstream proves responseHeaderTimeout
// actually bounds how long a single request waits on a reachable-but-frozen
// downstream (see the constant's own doc comment for the real-container
// measurement -- docker pause, 30s -- that motivated dropping it from 30s
// to 5s). A regression back to a much larger value would make this test's
// upper bound assertion fail well before an actual 30s wait would.
func TestReverseProxy_TimesOutOnHangingDownstream(t *testing.T) {
	addr, closeFn := hangingDownstream(t)
	defer closeFn()

	server := newProxyTestServer(createReverseProxy(addr, "some-service"))
	defer server.Close()

	start := time.Now()
	code := doRequest(t, server, "/ping")
	elapsed := time.Since(start)

	if code != http.StatusBadGateway {
		t.Fatalf("expected 502 once responseHeaderTimeout elapses, got %d", code)
	}
	if elapsed < responseHeaderTimeout-500*time.Millisecond {
		t.Fatalf("request returned suspiciously early (%s) -- expected to wait close to responseHeaderTimeout (%s)", elapsed, responseHeaderTimeout)
	}
	if elapsed > responseHeaderTimeout+3*time.Second {
		t.Fatalf("request took %s, more than responseHeaderTimeout (%s) plus slack -- timeout regressed to a much larger value?", elapsed, responseHeaderTimeout)
	}
}

// TestSetupServiceRoutes_BillingUsesBreaker proves the actual routing
// decision in setupServiceRoutes — not just the generic
// createReverseProxyWithBreaker helper — now sends billing through the
// breaker like every other implemented entry. Before this task, billing was
// special-cased in setupServiceRoutes to keep the plain, breaker-less proxy
// (see ADR-0006's open Roadmap item), so this test fails against that
// special case and passes once it's removed.
// TestSetupServiceRoutes_ForwardsPathUnchanged proves the Gateway acts as a
// transparent routing layer (see ADR-0025): the full incoming path,
// including the service's own routing segment (/auth, /products, /orders,
// /billing, /inventory, /notification), must reach the downstream service
// byte-for-byte.
// Before this fix, setupServiceRoutes stripped that segment before
// forwarding, which happened to still work for four services only because
// their own routes were mounted bare (not self-namespaced) -- this test
// fails against that old behavior for every service, not just inventory.
func TestSetupServiceRoutes_ForwardsPathUnchanged(t *testing.T) {
	tests := []struct {
		servicePath string
		requestPath string
	}{
		{"auth", "/auth/signup"},
		{"products", "/products/sellers/42"},
		{"orders", "/orders/createOrder"},
		{"billing", "/billing/api/payments/process"},
		{"inventory", "/inventory/123"},
		{"notification", "/notification/notifications/456"},
	}

	for _, tt := range tests {
		t.Run(tt.servicePath, func(t *testing.T) {
			var receivedPath string
			downstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				receivedPath = r.URL.Path
				w.WriteHeader(http.StatusOK)
			}))
			defer downstream.Close()

			original := services[tt.servicePath]
			services[tt.servicePath] = ServiceConfig{URL: downstream.URL, Name: original.Name, Implemented: true}
			defer func() { services[tt.servicePath] = original }()

			router := gin.New()
			setupServiceRoutes(router)
			server := httptest.NewServer(router)
			defer server.Close()

			doRequest(t, server, tt.requestPath)

			if receivedPath != tt.requestPath {
				t.Fatalf("expected downstream to receive %s unchanged, got %s", tt.requestPath, receivedPath)
			}
		})
	}
}

func TestSetupServiceRoutes_BillingUsesBreaker(t *testing.T) {
	downURL := closedPortURL(t)
	original := services["billing"]
	services["billing"] = ServiceConfig{URL: downURL, Name: "billing-service", Implemented: true}
	defer func() { services["billing"] = original }()

	router := gin.New()
	setupServiceRoutes(router)
	server := httptest.NewServer(router)
	defer server.Close()

	for i := 0; i < 5; i++ {
		code := doRequest(t, server, "/billing/ping")
		if code != http.StatusBadGateway {
			t.Fatalf("call %d: expected 502 from a still-closed breaker, got %d", i+1, code)
		}
	}

	code := doRequest(t, server, "/billing/ping")
	if code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503 (breaker open) on the 6th call through the billing route, got %d", code)
	}
}

// TestHealthCheck_ReflectsRealBreakerState proves /health reports each
// service's actual circuit breaker state instead of the static
// "implemented" config flag it used to report unconditionally (ADR-0010's
// Update, extended to the Go services): a service whose breaker has
// tripped open shows as "unavailable", not "implemented" as if nothing
// were wrong.
func TestHealthCheck_ReflectsRealBreakerState(t *testing.T) {
	downURL := closedPortURL(t)
	original := services["billing"]
	services["billing"] = ServiceConfig{URL: downURL, Name: "billing-service", Implemented: true}
	defer func() { services["billing"] = original }()

	router := gin.New()
	router.GET("/health", healthCheck)
	setupServiceRoutes(router)
	server := httptest.NewServer(router)
	defer server.Close()

	// Trip billing's breaker open.
	for i := 0; i < 6; i++ {
		doRequest(t, server, "/billing/ping")
	}

	resp, err := http.Get(server.URL + "/health")
	if err != nil {
		t.Fatalf("GET /health failed: %v", err)
	}
	defer resp.Body.Close()

	var body struct {
		Services map[string]string `json:"services"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("failed to decode /health response: %v", err)
	}

	if got := body.Services["billing"]; got != "unavailable" {
		t.Fatalf("expected billing to report \"unavailable\" with its breaker open, got %q", got)
	}
}
