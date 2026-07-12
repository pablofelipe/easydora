package messaging

import (
	"encoding/json"
	"testing"
	"time"

	"inventory-service/internal/models"
)

// Contract test: stock.insufficient (RabbitMQ order.exchange), published by
// this service's Outbox (ADR-0007). Consumed by orders-service.
func TestStockInsufficientEventConformsToSharedSchema(t *testing.T) {
	event := models.StockInsufficientEvent{
		OrderID:   "order-2",
		ProductID: "prod-1",
		Required:  5,
		Available: 2,
		Timestamp: time.Now(),
	}

	body, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("failed to marshal StockInsufficientEvent: %v", err)
	}

	var payload interface{}
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatalf("failed to unmarshal for validation: %v", err)
	}

	schema, err := loadSchema("stock-insufficient.schema.json")
	if err != nil {
		t.Fatalf("failed to load shared schema: %v", err)
	}

	if err := schema.Validate(payload); err != nil {
		t.Errorf("published StockInsufficientEvent violates shared schema: %v", err)
	}
}
