//go:build integration

package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"inventory-service/pkg/database"
)

// TestHealthHandler_ReturnsOKStatus proves healthHandlerFor's real
// connectivity probe succeeds against a real, reachable Postgres (CI Phase
// 2 service container) -- the counterpart to
// TestHealthHandler_ReturnsServiceUnavailableWhenDatabaseIsUnreachable in
// main_test.go, which proves the opposite path without needing live infra.
func TestHealthHandler_ReturnsOKStatus(t *testing.T) {
	db, err := database.InitPostgres()
	if err != nil {
		t.Fatalf("failed to connect to Postgres: %v", err)
	}
	defer db.Close()

	handler := healthHandlerFor(db)

	req := httptest.NewRequest(http.MethodGet, "/inventory/health", nil)
	rec := httptest.NewRecorder()

	handler(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("expected Content-Type application/json, got %q", ct)
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}
	if body["status"] != "OK" || body["database"] != "Connected" {
		t.Fatalf("unexpected body: %v", body)
	}
}
