package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestHealthHandler_ReturnsOKStatus proves the extracted health handler
// (shared by the bare /health route used by Docker's own HEALTHCHECK and
// the self-namespaced /inventory/health route used when the Gateway
// forwards the incoming path unchanged, see ADR-0025) writes a 200 with the
// expected JSON body regardless of which path it's mounted under.
func TestHealthHandler_ReturnsOKStatus(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/inventory/health", nil)
	rec := httptest.NewRecorder()

	healthHandler(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("expected Content-Type application/json, got %q", ct)
	}
	if body := rec.Body.String(); body != "{\"status\":\"OK\"}\n" {
		t.Fatalf("unexpected body: %q", body)
	}
}
