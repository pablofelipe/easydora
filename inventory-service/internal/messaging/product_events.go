package messaging

import (
	"inventory-service/internal/models"
	"inventory-service/internal/service"
	"log"
)

// applyProductCreatedEvent decides whether to create or update the
// inventory record for a product-created event, and applies it. Kept
// independent of transport so it can be tested directly (see
// product_events_behavior_test.go).
func applyProductCreatedEvent(event models.ProductCreatedEvent, inventoryService service.InventoryService) {
	// First, check whether it already exists
	inventory, _ := inventoryService.GetInventory(event.ProductID)
	if inventory == nil {
		log.Printf("Creating new inventory for product: %s", event.ProductID)
		if err := inventoryService.CreateInventory(event.ProductID, event.InitialStock); err != nil {
			log.Printf("Failed to create inventory for product %s: %v",
				event.ProductID, err)
		} else {
			log.Printf("Inventory created for product: %s", event.ProductID)
		}
	} else {
		log.Printf("Updating existing inventory for product: %s", event.ProductID)
		if err := inventoryService.UpdateInventory(event.ProductID, event.InitialStock); err != nil {
			log.Printf("Failed to update inventory for product %s: %v",
				event.ProductID, err)
		} else {
			log.Printf("Inventory updated for product: %s", event.ProductID)
		}
	}
}

// applyProductUpdatedEvent deactivates the inventory record when the
// product becomes inactive. Kept independent of transport so it can be
// tested directly (see product_events_behavior_test.go).
func applyProductUpdatedEvent(event models.ProductUpdatedEvent, inventoryService service.InventoryService) {
	if !event.Active {
		if err := inventoryService.DeactivateProduct(event.ProductID); err != nil {
			log.Printf("Failed to deactivate inventory for product %s: %v",
				event.ProductID, err)
		} else {
			log.Printf("Product deactivated in inventory: %s", event.ProductID)
		}
	} else {
		log.Printf("Product re-activated: %s", event.ProductID)
	}
}

// applyProductDeletedEvent removes the inventory record for a deleted
// product. Kept independent of transport so it can be tested directly (see
// product_events_behavior_test.go).
func applyProductDeletedEvent(event models.ProductDeletedEvent, inventoryService service.InventoryService) {
	if err := inventoryService.DeleteProduct(event.ProductID); err != nil {
		log.Printf("Failed to delete inventory for product %s: %v",
			event.ProductID, err)
	} else {
		log.Printf("Product removed from inventory: %s", event.ProductID)
	}
}
