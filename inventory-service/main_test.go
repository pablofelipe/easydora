package main

import (
	"database/sql"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"

	_ "github.com/lib/pq"
)

// closedPortDB opens a *sql.DB against a port guaranteed to refuse every
// connection (a real listener is started to get a free port, then
// immediately closed) -- sql.Open never actually dials until first use, so
// this only fails once healthHandlerFor's own PingContext runs, proving
// the unreachable-database path without needing a real Postgres.
func closedPortDB(t *testing.T) *sql.DB {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to open listener: %v", err)
	}
	addr := listener.Addr().String()
	listener.Close()

	db, err := sql.Open("postgres", "postgres://user:pass@"+addr+"/db?sslmode=disable&connect_timeout=1")
	if err != nil {
		t.Fatalf("failed to open db handle: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

// TestHealthHandler_ReturnsServiceUnavailableWhenDatabaseIsUnreachable
// proves the extracted health handler (shared by the bare /health route
// used by Docker's own HEALTHCHECK and the self-namespaced
// /inventory/health route used when the Gateway forwards the incoming
// path unchanged, see ADR-0025) performs a real connectivity probe instead
// of always answering 200. ADR-0010's Update: this endpoint used to report
// a hardcoded "OK" with no real dependency probe at all, the same
// shallow-liveness-check pattern already fixed for the four Spring
// services. The reachable-database/200 case is covered by
// TestHealthHandler_ReturnsOKStatus in main_health_integration_test.go
// (needs a real Postgres, so it's tagged integration) -- this test proves
// the opposite path without needing one.
func TestHealthHandler_ReturnsServiceUnavailableWhenDatabaseIsUnreachable(t *testing.T) {
	db := closedPortDB(t)
	handler := healthHandlerFor(db)

	req := httptest.NewRequest(http.MethodGet, "/inventory/health", nil)
	rec := httptest.NewRecorder()

	handler(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected status 503, got %d", rec.Code)
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}
	if body["status"] != "DOWN" || body["database"] != "Disconnected" {
		t.Fatalf("unexpected body: %v", body)
	}
}
