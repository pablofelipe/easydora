package messaging

import (
	"encoding/json"
	"testing"

	"inventory-service/internal/models"
)

// Contract test: the product.updated payload this service consumes
// (ConsumeProductUpdatedEvents, unmarshals into models.ProductUpdatedEvent)
// must conform to /schemas/json/product-updated.schema.json.
func TestProductUpdatedConsumerConformsToSharedSchema(t *testing.T) {
	wirePayload := []byte(`{
		"productId": "prod-1",
		"productName": "Widget",
		"price": 24.90,
		"active": true,
		"updatedAt": "2026-07-13T10:00:00Z"
	}`)

	var payload interface{}
	if err := json.Unmarshal(wirePayload, &payload); err != nil {
		t.Fatalf("failed to unmarshal for validation: %v", err)
	}

	schema, err := loadSchema("product-updated.schema.json")
	if err != nil {
		t.Fatalf("failed to load shared schema: %v", err)
	}
	if err := schema.Validate(payload); err != nil {
		t.Fatalf("example product.updated payload does not even match its own schema: %v", err)
	}

	var event models.ProductUpdatedEvent
	if err := json.Unmarshal(wirePayload, &event); err != nil {
		t.Fatalf("consumer failed to unmarshal a schema-conformant payload: %v", err)
	}

	if event.ProductID != "prod-1" || !event.Active {
		t.Errorf("consumer extracted unexpected values: %+v", event)
	}
}
