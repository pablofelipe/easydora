package messaging

import (
	"encoding/json"
	"testing"

	"inventory-service/internal/models"
)

// Contract test: the product.deleted payload this service consumes
// (ConsumeProductDeletedEvents, unmarshals into models.ProductDeletedEvent)
// must conform to /schemas/json/product-deleted.schema.json.
func TestProductDeletedConsumerConformsToSharedSchema(t *testing.T) {
	wirePayload := []byte(`{
		"productId": "prod-1",
		"deletedAt": "2026-07-13T10:00:00Z"
	}`)

	var payload interface{}
	if err := json.Unmarshal(wirePayload, &payload); err != nil {
		t.Fatalf("failed to unmarshal for validation: %v", err)
	}

	schema, err := loadSchema("product-deleted.schema.json")
	if err != nil {
		t.Fatalf("failed to load shared schema: %v", err)
	}
	if err := schema.Validate(payload); err != nil {
		t.Fatalf("example product.deleted payload does not even match its own schema: %v", err)
	}

	var event models.ProductDeletedEvent
	if err := json.Unmarshal(wirePayload, &event); err != nil {
		t.Fatalf("consumer failed to unmarshal a schema-conformant payload: %v", err)
	}

	if event.ProductID != "prod-1" {
		t.Errorf("consumer extracted unexpected values: %+v", event)
	}
}
