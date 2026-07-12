package messaging

import (
	"encoding/json"
	"testing"

	"inventory-service/internal/models"
)

// Contract test: the product.created payload this service consumes
// (ConsumeProductCreatedEvents, unmarshals into models.ProductCreatedEvent)
// must conform to /schemas/json/product-created.schema.json.
func TestProductCreatedConsumerConformsToSharedSchema(t *testing.T) {
	wirePayload := []byte(`{
		"productId": "prod-1",
		"productName": "Widget",
		"sellerId": "seller-1",
		"price": 19.90,
		"initialStock": 100,
		"createdAt": "2026-07-13T10:00:00Z"
	}`)

	var payload interface{}
	if err := json.Unmarshal(wirePayload, &payload); err != nil {
		t.Fatalf("failed to unmarshal for validation: %v", err)
	}

	schema, err := loadSchema("product-created.schema.json")
	if err != nil {
		t.Fatalf("failed to load shared schema: %v", err)
	}
	if err := schema.Validate(payload); err != nil {
		t.Fatalf("example product.created payload does not even match its own schema: %v", err)
	}

	var event models.ProductCreatedEvent
	if err := json.Unmarshal(wirePayload, &event); err != nil {
		t.Fatalf("consumer failed to unmarshal a schema-conformant payload: %v", err)
	}

	if event.ProductID != "prod-1" || event.SellerID != "seller-1" || event.InitialStock != 100 {
		t.Errorf("consumer extracted unexpected values: %+v", event)
	}
}
