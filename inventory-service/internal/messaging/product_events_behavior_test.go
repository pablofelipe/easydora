package messaging

import (
	"testing"

	"inventory-service/internal/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// Behavior contract for the products -> inventory hop (ADR-0007): receiving
// a product-created/updated/deleted event must create, update, deactivate
// or delete the matching inventory record. Neither Kafka nor RabbitMQ is
// referenced anywhere in this file.
//
// applyProductCreatedEvent/applyProductUpdatedEvent/applyProductDeletedEvent
// (product_events.go) are pure functions, independent of the transport that
// delivers the parsed event (RabbitMQ, ADR-0007). They accept a parsed event
// and the already-broker-agnostic service.InventoryService interface, so
// this test needs no broker of any kind.

func TestApplyProductCreatedEvent_CreatesInventoryWhenNoneExistsYet(t *testing.T) {
	svc := &mockInventoryService{}

	event := models.ProductCreatedEvent{
		ProductID:    "prod-1",
		ProductName:  "Widget",
		SellerID:     "seller-1",
		Price:        19.9,
		InitialStock: 10,
		CreatedAt:    "2026-07-05T10:00:00Z",
	}

	applyProductCreatedEvent(event, svc)

	require.Len(t, svc.createdProductIDs, 1)
	assert.Equal(t, "prod-1", svc.createdProductIDs[0])
	assert.Equal(t, 10, svc.createdQuantities[0])
}

func TestApplyProductUpdatedEvent_DeactivatesInventoryWhenProductBecomesInactive(t *testing.T) {
	svc := &mockInventoryService{}

	event := models.ProductUpdatedEvent{
		ProductID:   "prod-2",
		ProductName: "Widget",
		Price:       19.9,
		Active:      false,
		UpdatedAt:   "2026-07-05T10:00:00Z",
	}

	applyProductUpdatedEvent(event, svc)

	require.Len(t, svc.deactivatedProductIDs, 1)
	assert.Equal(t, "prod-2", svc.deactivatedProductIDs[0])
}

func TestApplyProductDeletedEvent_RemovesInventoryRecord(t *testing.T) {
	svc := &mockInventoryService{}

	event := models.ProductDeletedEvent{
		ProductID: "prod-3",
		DeletedAt: "2026-07-05T10:00:00Z",
	}

	applyProductDeletedEvent(event, svc)

	require.Len(t, svc.deletedProductIDs, 1)
	assert.Equal(t, "prod-3", svc.deletedProductIDs[0])
}
