package service

import (
	"fmt"
	"inventory-service/internal/models"
	"inventory-service/internal/repository"
	"log"
)

type InventoryService interface {
    GetInventory(productID string) (*models.Inventory, error)
    UpdateInventory(productID string, quantity int) error
    ReserveStock(command *models.ReserveStockCommand) (*models.StockReservedEvent, []*models.StockInsufficientEvent)
    ReleaseStock(command *models.ReleaseStockCommand) error
    DeactivateProduct(productID string) error
    DeleteProduct(productID string) error
}

type inventoryService struct {
    repo repository.InventoryRepository
}

func NewInventoryService(repo repository.InventoryRepository) *inventoryService {
    return &inventoryService{repo: repo}
}

func (s *inventoryService) GetInventory(productID string) (*models.Inventory, error) {
    return s.repo.GetByProductID(productID)
}

func (s *inventoryService) UpdateInventory(productID string, quantity int) error {
    return s.repo.UpdateQuantity(productID, quantity)
}

func (s *inventoryService) DeactivateProduct(productID string) error {
	log.Printf("Deactivating product in inventory: %s", productID)
	// Marcar como indisponível sem remover
	return s.repo.DeactivateProduct(productID)
}

func (s *inventoryService) DeleteProduct(productID string) error {
	log.Printf("Removing product from inventory: %s", productID)
	// Remover ou marcar como deletado
	return s.repo.DeleteProduct(productID)
}


func (s *inventoryService) ReleaseStock(command *models.ReleaseStockCommand) error {
    log.Printf("🔄 Releasing stock for order: %s", command.OrderID)
    
    var errors []string
    
    for _, item := range command.Items {
        err := s.repo.ReleaseStock(item.ProductID, item.Quantity)
        if err != nil {
            log.Printf("❌ Failed to release %d units of product %s: %v", 
                item.Quantity, item.ProductID, err)
            errors = append(errors, 
                fmt.Sprintf("product %s: %v", item.ProductID, err))
            continue // Tenta liberar os outros itens mesmo se um falhar
        }
        log.Printf("✅ Released %d units of product %s", 
            item.Quantity, item.ProductID)
    }
    
    if len(errors) > 0 {
        return fmt.Errorf("partial failure releasing stock: %v", errors)
    }
    
    return nil
}

func (s *inventoryService) ReserveStock(command *models.ReserveStockCommand) (*models.StockReservedEvent, []*models.StockInsufficientEvent) {
    var insufficientEvents []*models.StockInsufficientEvent
    var reservedItems []string

    // Try to reserve stock for each item
    for _, item := range command.Items {
        err := s.repo.ReserveStock(item.ProductID, item.Quantity)
        if err != nil {
            // If any item fails, release all previously reserved items
            for _, reservedProductID := range reservedItems {
                s.repo.ReleaseStock(reservedProductID, item.Quantity)
            }
            
            // Create insufficient event
            inventory, _ := s.repo.GetByProductID(item.ProductID)
            available := 0
            if inventory != nil {
                available = inventory.Quantity - inventory.Reserved
            }
            
            insufficientEvents = append(insufficientEvents, &models.StockInsufficientEvent{
                OrderID:   command.OrderID,
                ProductID: item.ProductID,
                Required:  item.Quantity,
                Available: available,
                Timestamp: command.Timestamp,
            })
            
            return &models.StockReservedEvent{
                OrderID:   command.OrderID,
                Success:   false,
                Message:   fmt.Sprintf("Failed to reserve stock for product %s", item.ProductID),
                Timestamp: command.Timestamp,
            }, insufficientEvents
        }
        reservedItems = append(reservedItems, item.ProductID)
    }

    // All items reserved successfully
    return &models.StockReservedEvent{
        OrderID:   command.OrderID,
        Success:   true,
        Message:   "Stock reserved successfully",
        Timestamp: command.Timestamp,
    }, nil
}