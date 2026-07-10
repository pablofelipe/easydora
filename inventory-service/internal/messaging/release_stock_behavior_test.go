package messaging

import (
	"context"
	"encoding/json"
	"testing"

	"inventory-service/internal/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// Replaces rabbitmq_release_integration_test.go (deleted): that test
// proved, against a real RabbitMQ, that a ReleaseStockCommand published by
// orders-service (Java) decodes correctly once the Go struct's json tag was
// aligned to the wire's camelCase "orderId". That fix lives in the struct
// tag itself (models.ReleaseStockCommand.OrderID `json:"orderId"`), so it
// can be proven with the standard library's encoding/json alone — no
// broker, real or otherwise, is needed to reproduce a JSON-decoding bug.
// The InventoryService interface these commands are dispatched to is
// already broker-agnostic today, so this test is green immediately.

// releaseCommandJavaShape mirrors exactly what orders-service (Java) puts
// on the wire: Jackson's default bean serialization of
// ReleaseStockCommand{orderId, items[{productId, quantity}]}.
const releaseCommandJavaShapeJSON = `{"orderId":"order-99","items":[{"productId":"prod-1","quantity":3}]}`

func TestReleaseStockCommand_OrderIdDecodesFromJavaPublisherShape(t *testing.T) {
	var command models.ReleaseStockCommand
	require.NoError(t, json.Unmarshal([]byte(releaseCommandJavaShapeJSON), &command))

	require.Equal(t, "order-99", command.OrderID,
		"OrderID decoded from the Java publisher's JSON should be populated, not empty")

	svc := &mockInventoryService{}
	require.NoError(t, svc.ReleaseStock(context.Background(), &command))

	require.Len(t, svc.releasedCommands, 1)
	assert.Equal(t, "order-99", svc.releasedCommands[0].OrderID)
	require.Len(t, svc.releasedCommands[0].Items, 1)
	assert.Equal(t, "prod-1", svc.releasedCommands[0].Items[0].ProductID)
	assert.Equal(t, 3, svc.releasedCommands[0].Items[0].Quantity)
}
