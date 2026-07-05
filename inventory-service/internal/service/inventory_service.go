package service

import (
	"fmt"
	"hash/fnv"
	"inventory-service/internal/models"
	"inventory-service/internal/repository"
	"log"
	"sync"
	"time"
)

type reservedItem struct {
    productID string
    quantity  int
}

const (
    // reservationCacheTTL bounds how long a processed order's outcome is
    // kept for idempotent retry detection. The RabbitMQ reserve-stock
    // consumer Nacks with requeue=true on a Kafka publish failure
    // (rabbitmq_consumer.go), which RabbitMQ redelivers to this consumer
    // almost immediately — no message-ttl/backoff is configured on that
    // queue. The slowest realistic retry path in this codebase is the
    // consumer's own reconnect loop in NewRabbitMQConsumer, which backs
    // off for up to 3s * 10 attempts = 30s. 10 minutes gives roughly a 20x
    // margin over that reconnect window, comfortably covering a full
    // container restart during a docker-compose redeploy too, without
    // caching every order forever.
    reservationCacheTTL = 10 * time.Minute

    // reservationCacheCleanupInterval controls how often the background
    // sweep reclaims expired entries.
    reservationCacheCleanupInterval = 1 * time.Minute

    // orderLockStripes is the number of stripes used to serialize
    // ReserveStock's check-cache -> reserve -> write-cache section per
    // OrderID. A fixed-size array of mutexes (rather than a
    // map[string]*sync.Mutex keyed by OrderID) is used deliberately: a
    // per-OrderID map would reintroduce the same unbounded-growth problem
    // the TTL cache was built to fix. Two different OrderIDs that hash to
    // the same stripe just serialize against each other unnecessarily;
    // that's a throughput cost, not a correctness issue.
    orderLockStripes = 256
)

// reservationOutcome is the cached result of a previously processed
// ReserveStockCommand, keyed by OrderID, so a redelivered command (e.g.
// RabbitMQ requeue after a Kafka publish failure) returns the original
// result instead of reserving stock a second time. The entry is only
// honored until expiresAt — see reservationCacheTTL.
type reservationOutcome struct {
    orderId           string
    success           bool
    insufficientEvent *models.StockInsufficientEvent
    expiresAt         time.Time
}

type InventoryService interface {
    CreateInventory(productID string, quantity int) error
    GetInventory(productID string) (*models.Inventory, error)
    UpdateInventory(productID string, quantity int) error
    ReserveStock(command *models.ReserveStockCommand) (orderId string, success bool, insufficientEvent *models.StockInsufficientEvent, err error)
    ReleaseStock(command *models.ReleaseStockCommand) error
    DeactivateProduct(productID string) error
    DeleteProduct(productID string) error
}

type inventoryService struct {
    repo repository.InventoryRepository

    mu              sync.Mutex
    processedOrders map[string]reservationOutcome
    ttl             time.Duration
    stopCleanup     chan struct{}

    orderLocks [orderLockStripes]sync.Mutex
}

// lockForOrder returns the stripe mutex responsible for serializing
// ReserveStock calls for the given OrderID. The same OrderID always maps
// to the same stripe, so concurrent redeliveries of one order always
// contend on the same mutex.
func (s *inventoryService) lockForOrder(orderID string) *sync.Mutex {
    h := fnv.New32a()
    h.Write([]byte(orderID))
    return &s.orderLocks[h.Sum32()%orderLockStripes]
}

func (s *inventoryService) CreateInventory(productID string, quantity int) error {
    log.Printf("Criando inventário para produto: %s, quantidade: %d", productID, quantity)
    
    // Chame um método do repository para criar
    return s.repo.CreateInventory(productID, quantity)
}

func NewInventoryService(repo repository.InventoryRepository) *inventoryService {
    return newInventoryServiceWithTTL(repo, reservationCacheTTL, reservationCacheCleanupInterval)
}

// newInventoryServiceWithTTL builds a service with an explicit idempotency
// cache TTL and cleanup-sweep interval. Production code should use
// NewInventoryService; this exists so tests can exercise cache expiry
// without waiting on the real production TTL.
func newInventoryServiceWithTTL(repo repository.InventoryRepository, ttl, cleanupInterval time.Duration) *inventoryService {
    s := &inventoryService{
        repo:            repo,
        processedOrders: make(map[string]reservationOutcome),
        ttl:             ttl,
        stopCleanup:     make(chan struct{}),
    }
    go s.cleanupExpiredLoop(cleanupInterval)
    return s
}

// cleanupExpiredLoop periodically reclaims expired idempotency cache
// entries so the cache doesn't grow unbounded with the number of orders
// ever processed, regardless of whether those orders are ever looked up
// again.
func (s *inventoryService) cleanupExpiredLoop(interval time.Duration) {
    ticker := time.NewTicker(interval)
    defer ticker.Stop()

    for {
        select {
        case now := <-ticker.C:
            s.evictExpired(now)
        case <-s.stopCleanup:
            return
        }
    }
}

func (s *inventoryService) evictExpired(now time.Time) {
    s.mu.Lock()
    defer s.mu.Unlock()

    for orderID, outcome := range s.processedOrders {
        if now.After(outcome.expiresAt) {
            delete(s.processedOrders, orderID)
        }
    }
}

// Close stops the background cleanup goroutine. Safe to call multiple
// times is not required by current callers; tests call it once via defer.
func (s *inventoryService) Close() {
    close(s.stopCleanup)
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
    log.Printf("Releasing stock for order: %s", command.OrderID)
    
    var errors []string
    
    for _, item := range command.Items {
        err := s.repo.ReleaseStock(item.ProductID, item.Quantity)
        if err != nil {
            log.Printf("Failed to release %d units of product %s: %v",
                item.Quantity, item.ProductID, err)
            errors = append(errors, 
                fmt.Sprintf("product %s: %v", item.ProductID, err))
            continue // Tenta liberar os outros itens mesmo se um falhar
        }
        log.Printf("Released %d units of product %s",
            item.Quantity, item.ProductID)
    }
    
    if len(errors) > 0 {
        return fmt.Errorf("partial failure releasing stock: %v", errors)
    }
    
    return nil
}

func (s *inventoryService) ReserveStock(command *models.ReserveStockCommand) (orderId string, success bool, insufficientEvent *models.StockInsufficientEvent, err error) {
    // Serialize the whole check-cache -> reserve -> write-cache section
    // per OrderID so concurrent redeliveries of the same order can't both
    // observe a cache miss and both reach the repository. A losing
    // goroutine blocks here, then re-checks the cache below once it gets
    // the lock and finds the winner's result already written.
    orderLock := s.lockForOrder(command.OrderID)
    orderLock.Lock()
    defer orderLock.Unlock()

    now := time.Now()

    s.mu.Lock()
    outcome, seen := s.processedOrders[command.OrderID]
    cacheHit := seen && now.Before(outcome.expiresAt)
    s.mu.Unlock()

    if cacheHit {
        log.Printf("[IDEMPOTENT] Order %s already processed, returning cached result instead of reserving again", command.OrderID)
        return outcome.orderId, outcome.success, outcome.insufficientEvent, nil
    }
    // seen && !cacheHit means the entry expired: treated as a fresh
    // delivery below. This is a known, accepted gap — see
    // TestReserveStock_RedeliveryAfterTTLExpiryDuplicatesReservation.

    orderId, success, insufficientEvent, err = s.doReserveStock(command)
    if err != nil {
        // Repo-level failure: no state changed, so a genuine retry must
        // reach the repository again, not be swallowed by the cache.
        return orderId, success, insufficientEvent, err
    }

    s.mu.Lock()
    s.processedOrders[command.OrderID] = reservationOutcome{
        orderId:           orderId,
        success:           success,
        insufficientEvent: insufficientEvent,
        expiresAt:         now.Add(s.ttl),
    }
    s.mu.Unlock()

    return orderId, success, insufficientEvent, err
}

func (s *inventoryService) doReserveStock(command *models.ReserveStockCommand) (orderId string, success bool, insufficientEvent *models.StockInsufficientEvent, err error) {
    log.Printf("Starting stock reservation for order: %s", command.OrderID)

    // Try to reserve stock for each item
    for _, item := range command.Items {
        log.Printf("  Attempting to reserve %d units of product %s",
            item.Quantity, item.ProductID)
        
        err = s.repo.ReserveStock(item.ProductID, item.Quantity)
        if err != nil {
            log.Printf("  Failed to reserve product %s: %v", item.ProductID, err)
            
            // Get available stock for reporting
            inventory, _ := s.repo.GetByProductID(item.ProductID)
            available := 0
            if inventory != nil {
                available = inventory.Quantity - inventory.Reserved
            }
            
            // Create insufficient event
            insufficientEvent = &models.StockInsufficientEvent{
                OrderID:   command.OrderID,
                ProductID: item.ProductID,
                Required:  item.Quantity,
                Available: available,
                Timestamp: time.Now(),
            }
            
            return command.OrderID, false, insufficientEvent, nil
        }
        
        log.Printf("  Successfully reserved %d units of product %s",
            item.Quantity, item.ProductID)
    }

    // All items reserved successfully
    log.Printf("All stock reserved successfully for order: %s", command.OrderID)
    
    return command.OrderID, true, nil, nil
}
