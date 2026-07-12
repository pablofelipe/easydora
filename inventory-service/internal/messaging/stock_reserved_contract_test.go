package messaging

import (
	"encoding/json"
	"testing"
	"time"

	"inventory-service/internal/models"
)

// Contract test: stock.reserved (RabbitMQ order.exchange), published by
// this service's Outbox (ADR-0007). Consumed by orders-service.
func TestStockReservedEventConformsToSharedSchema(t *testing.T) {
	event := models.StockReservedEvent{
		OrderID:   "order-1",
		Success:   true,
		Message:   "stock reserved",
		Timestamp: time.Now(),
	}

	body, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("failed to marshal StockReservedEvent: %v", err)
	}

	var payload interface{}
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatalf("failed to unmarshal for validation: %v", err)
	}

	schema, err := loadSchema("stock-reserved.schema.json")
	if err != nil {
		t.Fatalf("failed to load shared schema: %v", err)
	}

	if err := schema.Validate(payload); err != nil {
		t.Errorf("published StockReservedEvent violates shared schema: %v", err)
	}
}
