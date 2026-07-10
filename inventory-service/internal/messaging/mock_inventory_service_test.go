package messaging

import (
	"context"
	"sync"

	"inventory-service/internal/models"
)

// mockInventoryService is a shared test double for service.InventoryService,
// used by the behavior tests in this package. It records every call so
// tests can assert "did the service react correctly" without touching any
// broker.
type mockInventoryService struct {
	mu sync.Mutex

	createdProductIDs     []string
	createdQuantities     []int
	updatedProductIDs     []string
	updatedQuantities     []int
	deactivatedProductIDs []string
	deletedProductIDs     []string
	releasedCommands      []*models.ReleaseStockCommand

	reserveResult struct {
		orderID           string
		success           bool
		insufficientEvent *models.StockInsufficientEvent
		err               error
	}
}

func (m *mockInventoryService) CreateInventory(productID string, quantity int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.createdProductIDs = append(m.createdProductIDs, productID)
	m.createdQuantities = append(m.createdQuantities, quantity)
	return nil
}

func (m *mockInventoryService) GetInventory(productID string) (*models.Inventory, error) {
	return nil, nil
}

func (m *mockInventoryService) UpdateInventory(productID string, quantity int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.updatedProductIDs = append(m.updatedProductIDs, productID)
	m.updatedQuantities = append(m.updatedQuantities, quantity)
	return nil
}

func (m *mockInventoryService) ReserveStock(ctx context.Context, command *models.ReserveStockCommand) (string, bool, *models.StockInsufficientEvent, error) {
	return m.reserveResult.orderID, m.reserveResult.success, m.reserveResult.insufficientEvent, m.reserveResult.err
}

func (m *mockInventoryService) ReleaseStock(ctx context.Context, command *models.ReleaseStockCommand) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.releasedCommands = append(m.releasedCommands, command)
	return nil
}

func (m *mockInventoryService) DeactivateProduct(productID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.deactivatedProductIDs = append(m.deactivatedProductIDs, productID)
	return nil
}

func (m *mockInventoryService) DeleteProduct(productID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.deletedProductIDs = append(m.deletedProductIDs, productID)
	return nil
}
