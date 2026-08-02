//go:build integration

package messaging

import (
	"context"
	"fmt"
	"testing"
	"time"

	"inventory-service/internal/models"
	"inventory-service/internal/repository"
)

// TestReserveStockForOrder_RepeatedOrderIDIsIdempotentAtTheDatabaseLevel
// proves the fix for the gap documented in
// TestReserveStock_RedeliveryAfterTTLExpiryDuplicatesReservation
// (internal/service/inventory_service_test.go): inventoryService's
// in-memory idempotency cache only protects a redelivery arriving within
// its own TTL window (10 minutes in production) -- one arriving later, or
// after a process restart wipes the cache entirely, used to reserve stock
// a second time. PostgresRepository.ReserveStockForOrder now also checks
// inventory_schema.reservation_outcomes, a durable record written in the
// same transaction as the reservation itself, independent of any
// in-memory cache. This test calls the repository directly, twice, with
// no cache in front of it at all -- the worst case the TTL cache can't
// help with.
func TestReserveStockForOrder_RepeatedOrderIDIsIdempotentAtTheDatabaseLevel(t *testing.T) {
	db := openTestDB(t)
	defer db.Close()

	repo := repository.NewPostgresRepository(db)

	productID := fmt.Sprintf("db-idempotent-%d", time.Now().UnixNano())
	seedInventory(t, db, productID, 10, 0, true)

	orderID := "order-" + productID
	cmd := &models.ReserveStockCommand{
		OrderID: orderID,
		Items:   []models.ReserveStockItem{{ProductID: productID, Quantity: 5}},
	}

	success1, insufficient1, err1 := repo.ReserveStockForOrder(context.Background(), cmd)
	if err1 != nil || !success1 || insufficient1 != nil {
		t.Fatalf("first reservation unexpectedly failed: success=%v insufficient=%+v err=%v", success1, insufficient1, err1)
	}

	// Simulates a redelivery with no in-memory cache in front of it at all
	// -- the exact scenario a TTL-expired entry or a process restart
	// produces.
	success2, insufficient2, err2 := repo.ReserveStockForOrder(context.Background(), cmd)
	if err2 != nil || !success2 || insufficient2 != nil {
		t.Fatalf("second (redelivered) reservation unexpectedly failed: success=%v insufficient=%+v err=%v", success2, insufficient2, err2)
	}

	var reserved int
	if err := db.QueryRow(`SELECT reserved FROM inventory_schema.inventory WHERE product_id = $1`, productID).Scan(&reserved); err != nil {
		t.Fatalf("failed to read inventory: %v", err)
	}
	if reserved != 5 {
		t.Fatalf("expected reserved quantity to stay at 5 after a redelivered ReserveStockForOrder, got %d -- the database-level idempotency check did not catch the duplicate", reserved)
	}

	var outboxCount int
	if err := db.QueryRow(
		`SELECT COUNT(*) FROM inventory_schema.outbox_events WHERE routing_key = 'stock.reserved' AND payload LIKE '%' || $1 || '%'`,
		orderID,
	).Scan(&outboxCount); err != nil {
		t.Fatalf("failed to count outbox events: %v", err)
	}
	if outboxCount != 1 {
		t.Fatalf("expected exactly one stock.reserved outbox event for order %s, got %d", orderID, outboxCount)
	}
}

// TestReserveStockForOrder_RepeatedOrderIDReplaysInsufficientOutcome proves
// the same database-level idempotency check also replays a cached
// insufficient-stock outcome, not just a successful one, without
// re-evaluating current stock levels (which could have changed between
// the two deliveries and would otherwise give a redelivered command a
// different answer than the one already committed).
func TestReserveStockForOrder_RepeatedOrderIDReplaysInsufficientOutcome(t *testing.T) {
	db := openTestDB(t)
	defer db.Close()

	repo := repository.NewPostgresRepository(db)

	productID := fmt.Sprintf("db-idempotent-insufficient-%d", time.Now().UnixNano())
	seedInventory(t, db, productID, 3, 0, true)

	orderID := "order-" + productID
	cmd := &models.ReserveStockCommand{
		OrderID: orderID,
		Items:   []models.ReserveStockItem{{ProductID: productID, Quantity: 5}},
	}

	success1, insufficient1, err1 := repo.ReserveStockForOrder(context.Background(), cmd)
	if err1 != nil || success1 || insufficient1 == nil {
		t.Fatalf("first reservation was expected to be insufficient: success=%v insufficient=%+v err=%v", success1, insufficient1, err1)
	}

	// Stock now has enough available for the item -- if the redelivery
	// re-evaluated stock instead of replaying the cached outcome, it would
	// succeed instead of staying insufficient.
	if _, err := db.Exec(`UPDATE inventory_schema.inventory SET quantity = 20 WHERE product_id = $1`, productID); err != nil {
		t.Fatalf("failed to top up inventory: %v", err)
	}

	success2, insufficient2, err2 := repo.ReserveStockForOrder(context.Background(), cmd)
	if err2 != nil {
		t.Fatalf("second (redelivered) reservation unexpectedly errored: %v", err2)
	}
	if success2 || insufficient2 == nil {
		t.Fatalf("expected the redelivered command to replay the cached insufficient outcome, got success=%v insufficient=%+v", success2, insufficient2)
	}
	if insufficient2.ProductID != productID || insufficient2.Required != 5 || insufficient2.Available != 3 {
		t.Fatalf("replayed insufficient outcome does not match the original: %+v", insufficient2)
	}
}
