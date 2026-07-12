package messaging

import (
	"encoding/json"
	"testing"

	"inventory-service/internal/models"
)

// Contract test: the stock.reserve payload this service consumes (a
// command, not a fact-event -- see ADR-0034's naming precedent) must
// conform to /schemas/json/stock-reserve.schema.json. This schema
// deliberately omits the Timestamp field this service's own
// ReserveStockCommand struct declares -- orders-service's producer never
// actually sends it (see the schema's own description).
func TestStockReserveCommandConsumerConformsToSharedSchema(t *testing.T) {
	wirePayload := []byte(`{
		"orderId": "order-1",
		"items": [
			{"productId": "prod-1", "quantity": 2}
		]
	}`)

	var payload interface{}
	if err := json.Unmarshal(wirePayload, &payload); err != nil {
		t.Fatalf("failed to unmarshal for validation: %v", err)
	}

	schema, err := loadSchema("stock-reserve.schema.json")
	if err != nil {
		t.Fatalf("failed to load shared schema: %v", err)
	}
	if err := schema.Validate(payload); err != nil {
		t.Fatalf("example stock.reserve payload does not even match its own schema: %v", err)
	}

	var command models.ReserveStockCommand
	if err := json.Unmarshal(wirePayload, &command); err != nil {
		t.Fatalf("consumer failed to unmarshal a schema-conformant payload: %v", err)
	}

	if command.OrderID != "order-1" || len(command.Items) != 1 || command.Items[0].ProductID != "prod-1" {
		t.Errorf("consumer extracted unexpected values: %+v", command)
	}
}
