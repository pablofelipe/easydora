package main

import (
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

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

// TestPlainProxy_DoesNotShortCircuit proves createReverseProxy (used for
// billing-service, deliberately out of this round's scope) keeps calling
// the downstream on every request — no breaker, no 503 short-circuit —
// even after repeated failures. This is the "Red" counterpart to the three
// tests above: swap createReverseProxyWithBreaker for createReverseProxy in
// any of them and the 503 assertions stop being true.
func TestPlainProxy_DoesNotShortCircuit(t *testing.T) {
	addr, callCount, closeFn := flakyDownstream(t)
	defer closeFn()

	server := newProxyTestServer(createReverseProxy(addr, "billing-service"))
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
