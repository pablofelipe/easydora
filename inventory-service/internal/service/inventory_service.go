package service

import (
	"context"
	"fmt"
	"hash/fnv"
	"easydora/correlation-commons"
	"inventory-service/internal/models"
	"inventory-service/internal/repository"
	"log"
	"os"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

var logger = correlation.NewLogger(os.Stdout, "inventory-service")

// Business metric (ADR-0036): infra-level metrics already answer "is the
// system healthy"; this one answers a question infra can't -- how often a
// reservation actually fails for lack of stock. Incremented exactly once
// per real (non-cached) outcome in doReserveStock, never on a redelivery
// served from processedOrders.
var inventoryReservationsFailedCounter = promauto.NewCounter(prometheus.CounterOpts{
	Name: "inventory_reservations_failed_total",
	Help: "Total stock reservations that failed due to insufficient stock.",
})

// idempotentDuplicateDetectedCounter answers a question no existing metric
// can: how often the idempotency cache actually catches a duplicate
// command delivery, for ReserveStock or ReleaseStock. Before this metric,
// the redelivery/duplication behavior these caches guard against (and the
// known, accepted residual gap once a cache entry's TTL expires — see
// TestReserveStock_RedeliveryAfterTTLExpiryDuplicatesReservation) was only
// provable by unit test, never observable in a running instance.
// Incremented exactly once per cache hit, never on a first (non-duplicate)
// delivery.
var idempotentDuplicateDetectedCounter = promauto.NewCounterVec(prometheus.CounterOpts{
	Name: "inventory_idempotent_duplicate_detected_total",
	Help: "Total duplicate command deliveries caught by the idempotency cache, by operation.",
}, []string{"operation"})

const (
    // reservationCacheTTL bounds how long a processed order's outcome is
    // kept for idempotent retry detection. The RabbitMQ reserve-stock
    // consumer Nacks with requeue=true whenever ReserveStockForOrder
    // returns a repository-level error (rabbitmq_consumer.go), which
    // RabbitMQ redelivers to this consumer almost immediately — no
    // message-ttl/backoff is configured on that queue. The slowest
    // realistic retry path in this codebase is the
    // consumer's own reconnect loop in NewRabbitMQConsumer, which backs
    // off for up to 3s * 10 attempts = 30s. 10 minutes gives roughly a 20x
    // margin over that reconnect window, comfortably covering a full
    // container restart during a docker-compose redeploy too, without
    // caching every order forever. ReleaseStock's idempotency cache
    // (processedReleases) shares this same constant: the redelivery
    // timing it protects against is identical, since both commands travel
    // through the same consumer/reconnect path.
    reservationCacheTTL = 10 * time.Minute

    // reservationCacheCleanupInterval controls how often the background
    // sweep reclaims expired entries from both processedOrders and
    // processedReleases.
    reservationCacheCleanupInterval = 1 * time.Minute

    // orderLockStripes is the number of stripes used to serialize each of
    // ReserveStock's and ReleaseStock's own check-cache -> act ->
    // write-cache section per OrderID. A fixed-size array of mutexes
    // (rather than a map[string]*sync.Mutex keyed by OrderID) is used
    // deliberately: a per-OrderID map would reintroduce the same
    // unbounded-growth problem the TTL caches were built to fix. Two
    // different OrderIDs that hash to the same stripe just serialize
    // against each other unnecessarily; that's a throughput cost, not a
    // correctness issue.
    orderLockStripes = 256
)

// reservationOutcome is the cached result of a previously processed
// ReserveStockCommand, keyed by OrderID, so a redelivered command (e.g.
// RabbitMQ requeue after a repository-level error) returns the original
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
    ReserveStock(ctx context.Context, command *models.ReserveStockCommand) (orderId string, success bool, insufficientEvent *models.StockInsufficientEvent, err error)
    ReleaseStock(ctx context.Context, command *models.ReleaseStockCommand) error
    DeactivateProduct(productID string) error
    DeleteProduct(productID string) error
}

type inventoryService struct {
    repo repository.InventoryRepository

    mu                sync.Mutex
    processedOrders   map[string]reservationOutcome
    processedReleases map[string]time.Time
    ttl               time.Duration
    stopCleanup       chan struct{}

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
    log.Printf("Creating inventory for product: %s, quantity: %d", productID, quantity)

    // Call the repository method to create it
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
        repo:              repo,
        processedOrders:   make(map[string]reservationOutcome),
        processedReleases: make(map[string]time.Time),
        ttl:               ttl,
        stopCleanup:       make(chan struct{}),
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
    for orderID, expiresAt := range s.processedReleases {
        if now.After(expiresAt) {
            delete(s.processedReleases, orderID)
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
	// Mark as unavailable without removing
	return s.repo.DeactivateProduct(productID)
}

func (s *inventoryService) DeleteProduct(productID string) error {
	log.Printf("Removing product from inventory: %s", productID)
	// Remove or mark as deleted
	return s.repo.DeleteProduct(productID)
}


func (s *inventoryService) ReleaseStock(ctx context.Context, command *models.ReleaseStockCommand) error {
    // Serialize the whole check-cache -> release -> write-cache section per
    // OrderID, the same way ReserveStock does — see lockForOrder.
    orderLock := s.lockForOrder(command.OrderID)
    orderLock.Lock()
    defer orderLock.Unlock()

    now := time.Now()

    s.mu.Lock()
    expiresAt, seen := s.processedReleases[command.OrderID]
    cacheHit := seen && now.Before(expiresAt)
    s.mu.Unlock()

    if cacheHit {
        idempotentDuplicateDetectedCounter.WithLabelValues("release").Inc()
        log.Printf("[IDEMPOTENT] Release for order %s already processed, skipping duplicate release", command.OrderID)
        correlation.Info(logger, ctx, "release already processed, skipping duplicate", "event", "stock.release.duplicate", "aggregateId", command.OrderID)
        return nil
    }
    // seen && !cacheHit means the entry expired: treated as a fresh
    // delivery below — the same known, accepted residual gap
    // ReserveStock's own cache has (see
    // TestReserveStock_RedeliveryAfterTTLExpiryDuplicatesReservation).

    correlation.Info(logger, ctx, "releasing stock", "event", "stock.release.received", "aggregateId", command.OrderID)

    var errors []string

    for _, item := range command.Items {
        err := s.repo.ReleaseStock(item.ProductID, item.Quantity)
        if err != nil {
            log.Printf("Failed to release %d units of product %s: %v",
                item.Quantity, item.ProductID, err)
            errors = append(errors,
                fmt.Sprintf("product %s: %v", item.ProductID, err))
            continue // Try to release the remaining items even if one fails
        }
        log.Printf("Released %d units of product %s",
            item.Quantity, item.ProductID)
    }

    if len(errors) > 0 {
        // Partial/full failure: no cache write, so a genuine retry reaches
        // the repository again instead of being swallowed by the cache.
        return fmt.Errorf("partial failure releasing stock: %v", errors)
    }

    s.mu.Lock()
    s.processedReleases[command.OrderID] = now.Add(s.ttl)
    s.mu.Unlock()

    return nil
}

func (s *inventoryService) ReserveStock(ctx context.Context, command *models.ReserveStockCommand) (orderId string, success bool, insufficientEvent *models.StockInsufficientEvent, err error) {
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
        idempotentDuplicateDetectedCounter.WithLabelValues("reserve").Inc()
        log.Printf("[IDEMPOTENT] Order %s already processed, returning cached result instead of reserving again", command.OrderID)
        return outcome.orderId, outcome.success, outcome.insufficientEvent, nil
    }
    // seen && !cacheHit means the entry expired: treated as a fresh
    // delivery below. This is a known, accepted gap — see
    // TestReserveStock_RedeliveryAfterTTLExpiryDuplicatesReservation.

    orderId, success, insufficientEvent, err = s.doReserveStock(ctx, command)
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

func (s *inventoryService) doReserveStock(ctx context.Context, command *models.ReserveStockCommand) (orderId string, success bool, insufficientEvent *models.StockInsufficientEvent, err error) {
    correlation.Info(logger, ctx, "starting stock reservation", "event", "stock.reserve.received", "aggregateId", command.OrderID)

    // ReserveStockForOrder reserves all items and writes the outbox event
    // for the outcome in the same Postgres transaction (Outbox Pattern,
    // ADR-0007) — the messaging layer no longer builds or publishes this
    // event itself; it's already durably recorded by the time this
    // returns.
    success, insufficientEvent, err = s.repo.ReserveStockForOrder(ctx, command)
    if err != nil {
        log.Printf("Failed to reserve stock for order %s: %v", command.OrderID, err)
        return command.OrderID, false, nil, err
    }

    if success {
        correlation.Info(logger, ctx, "stock reserved", "event", "stock.reserved", "aggregateId", command.OrderID)
    } else {
        correlation.Info(logger, ctx, "stock reservation insufficient", "event", "stock.insufficient", "aggregateId", command.OrderID)
        inventoryReservationsFailedCounter.Inc()
    }

    return command.OrderID, success, insufficientEvent, nil
}
