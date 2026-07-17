//go:build integration

package messaging

import (
	"encoding/json"
	"fmt"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"

	"inventory-service/internal/models"
	"inventory-service/internal/repository"
	"inventory-service/internal/service"
)

// Proves the exact gap found in the RabbitMQ resilience investigation
// (docs/adr/0038-infrastructure-startup-resilience.md's Update): before
// this test existed, forcibly closing RabbitMQConsumer's connection left
// every consumer permanently dead for the rest of the process's life, with
// no crash, no log, and no automatic recovery. It now proves the opposite,
// against a real broker -- not a mock standing in for one.
func TestConsumeReserveStockCommand_ResumesAfterConnectionDropped(t *testing.T) {
	db := openTestDB(t)
	defer db.Close()

	repo := repository.NewPostgresRepository(db)
	svc := service.NewInventoryService(repo)

	productID := fmt.Sprintf("reconnect-%d", time.Now().UnixNano())
	seedInventory(t, db, productID, 10, 0, true)

	consumer, err := NewRabbitMQConsumer()
	if err != nil {
		t.Fatalf("failed to connect consumer: %v", err)
	}
	defer consumer.Close()
	if err := consumer.SetupOrderExchange(); err != nil {
		t.Fatalf("failed to set up order.exchange: %v", err)
	}

	// Without its own OutboxPublisher, this test's two reservations would
	// leave unpublished stock.reserved rows behind for whichever later
	// test's OutboxPublisher happens to poll next -- and since every
	// test's observation queue binds the same literal "stock.reserved"
	// routing key (a topic exchange has no productId/orderId segment to
	// scope it by), that publisher would misdeliver this test's stale
	// backlog into a different test's assertion. Draining it here, before
	// any later queue binds to that routing key, means the exchange has
	// nothing to route it to and simply drops it -- exactly what an
	// already-gone test's own events should do.
	outbox, err := consumer.StartOutboxPublisher(repo)
	if err != nil {
		t.Fatalf("failed to start outbox publisher: %v", err)
	}
	defer outbox.Stop()

	_, pubCh := openTestChannel(t)
	defer pubCh.Close()
	declareTestQueue(t, pubCh, "inventory.reserve.queue", "order.exchange", "stock.reserve")
	go consumer.ConsumeReserveStockCommands(svc)

	publishReserve := func(orderID string, quantity int) {
		command := models.ReserveStockCommand{
			OrderID: orderID,
			Items:   []models.ReserveStockItem{{ProductID: productID, Quantity: quantity}},
		}
		body, _ := json.Marshal(command)
		if err := pubCh.Publish("order.exchange", "stock.reserve", false, false, amqp.Publishing{
			ContentType: "application/json",
			Body:        body,
		}); err != nil {
			t.Fatalf("failed to publish ReserveStockCommand: %v", err)
		}
	}

	reservedEquals := func(expected int) bool {
		var r int
		if err := db.QueryRow(`SELECT reserved FROM inventory_schema.inventory WHERE product_id = $1`, productID).Scan(&r); err != nil {
			return false
		}
		return r == expected
	}

	// Baseline: the consumer works before anything is disrupted.
	publishReserve("order-before-drop-"+productID, 2)
	if !awaitCondition(10*time.Second, func() bool { return reservedEquals(2) }) {
		t.Fatal("baseline reservation before the connection drop never landed")
	}

	// Simulate exactly what a RabbitMQ broker restart does to an
	// already-connected client: the TCP connection dies out from under it.
	// This is the same class of event ADR-0040's no-PersistentVolume
	// RabbitMQ produces in Kubernetes -- forcing it here, against a real
	// broker, is more reliable than actually restarting the broker
	// process mid-test.
	staleConn := consumer.currentConn()
	if err := staleConn.Close(); err != nil {
		t.Fatalf("failed to force-close the connection: %v", err)
	}

	// watchConnection must notice and redial on its own -- no test code
	// pokes it. Wait for the connection object itself to change before
	// publishing the next command, so this test isn't racing the
	// reconnect.
	reconnected := awaitCondition(15*time.Second, func() bool {
		return consumer.currentConn() != staleConn && !consumer.currentConn().IsClosed()
	})
	if !reconnected {
		t.Fatal("watchConnection did not replace the dropped connection within 15s")
	}

	// The consumer goroutine's own delivery channel closed when the
	// connection died; runConsumerLoop must have re-registered itself
	// against the new connection on its own for this to ever arrive.
	publishReserve("order-after-drop-"+productID, 3)
	if !awaitCondition(15*time.Second, func() bool { return reservedEquals(5) }) {
		t.Fatal("reservation published after the connection drop was never processed -- consumer did not resume")
	}

	// Drain this test's own outbox rows before returning: the deferred
	// outbox.Stop() above fires as soon as this function returns, which is
	// sooner than the publisher's own 5s poll tick unless we wait for it
	// here -- otherwise these two rows are still unpublished when this
	// test ends, and whichever later test's OutboxPublisher polls next
	// inherits (and misdelivers into its own assertion queue) this test's
	// backlog instead of its own event. No queue is bound to
	// "stock.reserved" yet at this point, so the exchange has nowhere to
	// route these once published and simply drops them.
	outboxPublished := func(orderID string) bool {
		var count int
		if err := db.QueryRow(`SELECT count(*) FROM inventory_schema.outbox_events WHERE payload LIKE '%' || $1 || '%' AND published_at IS NOT NULL`, orderID).Scan(&count); err != nil {
			return false
		}
		return count > 0
	}
	if !awaitCondition(10*time.Second, func() bool {
		return outboxPublished("order-before-drop-"+productID) && outboxPublished("order-after-drop-"+productID)
	}) {
		t.Fatal("this test's own outbox events were not published before it returned -- would leak into a later test's assertion queue")
	}
}
