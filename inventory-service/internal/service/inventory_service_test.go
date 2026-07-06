package service

import (
	"fmt"
	"sync"
	"testing"
	"time"

	"inventory-service/internal/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// mockInventoryRepository is a minimal in-memory stand-in for
// repository.InventoryRepository, used to keep these tests hermetic
// (no live Postgres required). It has its own mutex so that concurrency
// tests exercise races in the service under test, not spurious races in
// the mock itself.
type mockInventoryRepository struct {
	mu                sync.Mutex
	inventory         map[string]*models.Inventory
	reserveStockCalls int
}

func newMockInventoryRepository() *mockInventoryRepository {
	return &mockInventoryRepository{
		inventory: make(map[string]*models.Inventory),
	}
}

func (m *mockInventoryRepository) GetByProductID(productID string) (*models.Inventory, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.inventory[productID], nil
}

func (m *mockInventoryRepository) GetAvailableByProductID(productID string) (*models.Inventory, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, ok := m.inventory[productID]
	if !ok || !inv.Available || inv.Deleted {
		return nil, fmt.Errorf("product not available or not found: %s", productID)
	}
	return inv, nil
}

func (m *mockInventoryRepository) UpdateQuantity(productID string, newQuantity int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, ok := m.inventory[productID]
	if !ok {
		return fmt.Errorf("product not found: %s", productID)
	}
	inv.Quantity = newQuantity
	return nil
}

func (m *mockInventoryRepository) ReserveStockForOrder(command *models.ReserveStockCommand) (bool, *models.StockInsufficientEvent, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.reserveStockCalls++

	for _, item := range command.Items {
		inv, ok := m.inventory[item.ProductID]
		if !ok {
			return false, nil, fmt.Errorf("product not found: %s", item.ProductID)
		}

		available := inv.Quantity - inv.Reserved
		if !inv.Available || inv.Deleted || available < item.Quantity {
			return false, &models.StockInsufficientEvent{
				OrderID:   command.OrderID,
				ProductID: item.ProductID,
				Required:  item.Quantity,
				Available: available,
				Timestamp: time.Now(),
			}, nil
		}
	}

	for _, item := range command.Items {
		m.inventory[item.ProductID].Reserved += item.Quantity
	}

	return true, nil, nil
}

func (m *mockInventoryRepository) FindUnpublishedOutboxEvents() ([]models.OutboxEvent, error) {
	return nil, nil
}

func (m *mockInventoryRepository) MarkOutboxEventPublished(id int64) error {
	return nil
}

func (m *mockInventoryRepository) ReleaseStock(productID string, quantity int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, ok := m.inventory[productID]
	if !ok {
		return fmt.Errorf("product not found: %s", productID)
	}
	inv.Reserved -= quantity
	return nil
}

func (m *mockInventoryRepository) DeactivateProduct(productID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, ok := m.inventory[productID]
	if !ok {
		return fmt.Errorf("product not found: %s", productID)
	}
	inv.Available = false
	return nil
}

func (m *mockInventoryRepository) DeleteProduct(productID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, ok := m.inventory[productID]
	if !ok {
		return fmt.Errorf("product not found: %s", productID)
	}
	inv.Deleted = true
	return nil
}

func (m *mockInventoryRepository) IsProductAvailable(productID string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, ok := m.inventory[productID]
	if !ok {
		return false, nil
	}
	return inv.Available && !inv.Deleted, nil
}

func (m *mockInventoryRepository) CreateInventory(productID string, quantity int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.inventory[productID] = &models.Inventory{
		ProductID: productID,
		Quantity:  quantity,
		Available: true,
	}
	return nil
}

func (m *mockInventoryRepository) callCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.reserveStockCalls
}

func (m *mockInventoryRepository) reservedFor(productID string) int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.inventory[productID].Reserved
}

// TestReserveStock_RetryDoesNotDuplicateReservation reproduces the
// catalogued idempotency bug: RabbitMQ redelivers a ReserveStockCommand
// (e.g. because the consumer crashed or the connection dropped after the
// DB commit but before the Ack, triggering a Nack+requeue) and the same
// order gets its stock reserved twice.
func TestReserveStock_RetryDoesNotDuplicateReservation(t *testing.T) {
	repo := newMockInventoryRepository()
	repo.inventory["prod-1"] = &models.Inventory{
		ProductID: "prod-1",
		Quantity:  10,
		Reserved:  0,
		Available: true,
	}

	svc := NewInventoryService(repo)
	defer svc.Close()

	cmd := &models.ReserveStockCommand{
		OrderID: "order-1",
		Items: []models.ReserveStockItem{
			{ProductID: "prod-1", Quantity: 5},
		},
	}

	// First delivery: succeeds, reserves 5 units.
	_, success1, _, err1 := svc.ReserveStock(cmd)
	require.NoError(t, err1)
	require.True(t, success1)

	// Simulated redelivery of the exact same command for the same order
	// (what happens today when the consumer crashes or the connection
	// drops after the Postgres commit but before the Ack, and RabbitMQ
	// requeues the message).
	_, success2, _, err2 := svc.ReserveStock(cmd)
	require.NoError(t, err2)
	require.True(t, success2)

	assert.Equal(t, 1, repo.reserveStockCalls,
		"ReserveStock on the repository should only be invoked once per order; a second invocation means the retry double-reserved stock")
	assert.Equal(t, 5, repo.inventory["prod-1"].Reserved,
		"reserved quantity should reflect a single reservation of 5 units, not a duplicate reservation of 10")
}

// TestReserveStock_CacheDoesNotGrowUnboundedWithVolume proves the
// processed-order idempotency cache added to fix the retry-duplication bug
// does not itself become a memory leak: every distinct OrderID it ever
// sees must not be retained forever. 20,000 is an arbitrary "large enough
// to show the asymptotic trend" volume, not a realistic production
// estimate.
//
// Against the original fix (a plain map with no expiry), this fails with
// cacheSize == orderCount: every processed order is retained forever, i.e.
// the cache grows 1:1 and unbounded with volume.
func TestReserveStock_CacheDoesNotGrowUnboundedWithVolume(t *testing.T) {
	repo := newMockInventoryRepository()
	repo.inventory["prod-1"] = &models.Inventory{
		ProductID: "prod-1",
		Quantity:  1_000_000,
		Reserved:  0,
		Available: true,
	}

	// Fast TTL/cleanup so the test doesn't need to wait on the real
	// production TTL (chosen and justified next to reservationCacheTTL in
	// inventory_service.go) to observe the eviction behavior.
	svc := newInventoryServiceWithTTL(repo, 20*time.Millisecond, 10*time.Millisecond)
	defer svc.Close()

	const orderCount = 20_000
	for i := 0; i < orderCount; i++ {
		cmd := &models.ReserveStockCommand{
			OrderID: fmt.Sprintf("order-%d", i),
			Items:   []models.ReserveStockItem{{ProductID: "prod-1", Quantity: 1}},
		}
		_, success, _, err := svc.ReserveStock(cmd)
		require.NoError(t, err)
		require.True(t, success)
	}

	// Give every entry time to pass its TTL and let the background sweep
	// (ticking every 10ms) reclaim them.
	time.Sleep(200 * time.Millisecond)

	svc.mu.Lock()
	cacheSize := len(svc.processedOrders)
	svc.mu.Unlock()

	assert.Lessf(t, cacheSize, orderCount/100,
		"processed-order cache should be reclaimed by TTL expiry, not retain every order forever (got %d entries for %d orders processed)",
		cacheSize, orderCount)
}

// TestReserveStock_RedeliveryAfterTTLExpiryDuplicatesReservation documents
// a known, accepted residual gap: the idempotency cache only protects
// against retries that arrive within its TTL window. A redelivery that
// shows up after the entry has expired (e.g. a message reprocessed late
// from a dead-letter queue, or a service restart followed by a slow
// broker reconnect) is indistinguishable from a first delivery and will
// reserve stock again. This is not fixed here: the Outbox Pattern
// (ReserveStockForOrder, ADR-0007) closes a different gap — it guarantees
// the stock.reserved/stock.insufficient event is never lost once a
// reservation commits — but it doesn't make message redelivery itself
// idempotent, so this residual gap remains open. This test only proves
// the current behavior matches what the README claims, instead of
// leaving it as an unverified assumption.
func TestReserveStock_RedeliveryAfterTTLExpiryDuplicatesReservation(t *testing.T) {
	repo := newMockInventoryRepository()
	repo.inventory["prod-1"] = &models.Inventory{
		ProductID: "prod-1",
		Quantity:  10,
		Reserved:  0,
		Available: true,
	}

	// Cleanup interval set to an hour so the background sweep cannot fire
	// during the test — this isolates the read-time expiry check in
	// ReserveStock as the thing under test, not the sweep goroutine.
	svc := newInventoryServiceWithTTL(repo, 20*time.Millisecond, time.Hour)
	defer svc.Close()

	cmd := &models.ReserveStockCommand{
		OrderID: "order-1",
		Items:   []models.ReserveStockItem{{ProductID: "prod-1", Quantity: 5}},
	}

	_, success1, _, err1 := svc.ReserveStock(cmd)
	require.NoError(t, err1)
	require.True(t, success1)

	// Wait past the TTL window before the "redelivery" arrives.
	time.Sleep(30 * time.Millisecond)

	_, success2, _, err2 := svc.ReserveStock(cmd)
	require.NoError(t, err2)
	require.True(t, success2)

	assert.Equal(t, 2, repo.reserveStockCalls,
		"a redelivery arriving after the TTL window is expected to duplicate the reservation today — documented residual gap, not a regression")
	assert.Equal(t, 10, repo.inventory["prod-1"].Reserved,
		"reserved quantity doubles once the idempotency cache entry has expired")
}

// TestReserveStock_ConcurrentRedeliveriesOfSameOrderReserveOnce reproduces
// the real race left by the TTL fix: the cache-check and the
// cache-write are two separate critical sections, with the actual
// repository call happening in between while the lock is released. Two
// truly concurrent redeliveries of the same OrderID (not sequential, like
// the other tests here) can both observe a cache miss and both reach the
// repository before either has written its result back.
//
// All N goroutines are released together via a closed channel, right
// after being parked on it with the WaitGroup counting them as started,
// to get them calling ReserveStock as close to simultaneously as the Go
// scheduler allows.
func TestReserveStock_ConcurrentRedeliveriesOfSameOrderReserveOnce(t *testing.T) {
	const concurrentDeliveries = 50

	repo := newMockInventoryRepository()
	repo.inventory["prod-1"] = &models.Inventory{
		ProductID: "prod-1",
		Quantity:  1000,
		Reserved:  0,
		Available: true,
	}

	svc := NewInventoryService(repo)
	defer svc.Close()

	cmd := &models.ReserveStockCommand{
		OrderID: "order-1",
		Items:   []models.ReserveStockItem{{ProductID: "prod-1", Quantity: 5}},
	}

	start := make(chan struct{})
	var ready, wg sync.WaitGroup
	ready.Add(concurrentDeliveries)
	wg.Add(concurrentDeliveries)

	for i := 0; i < concurrentDeliveries; i++ {
		go func() {
			defer wg.Done()
			ready.Done()
			<-start // every goroutine blocks here until released at once

			_, success, _, err := svc.ReserveStock(cmd)
			assert.NoError(t, err)
			assert.True(t, success)
		}()
	}

	ready.Wait() // all goroutines are parked on <-start
	close(start) // release them together
	wg.Wait()

	assert.Equal(t, 1, repo.callCount(),
		"N concurrent redeliveries of the same OrderID should collapse into a single repository call, got %d", repo.callCount())
	assert.Equal(t, 5, repo.reservedFor("prod-1"),
		"reserved quantity should reflect a single reservation of 5 units even under concurrent redelivery")
}
