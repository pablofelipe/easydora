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

// These tests drive the real order.exchange (stock.reserve/stock.release),
// the real ReserveStockCommand/ReleaseStockCommand consumers, and — for the
// reserve path — the real Outbox (ReserveStockForOrder writes the
// stock.reserved/stock.insufficient row atomically with the reservation,
// see PostgresRepository.ReserveStockForOrder) against real Postgres and
// RabbitMQ (CI Phase 2 service containers).

func TestReserveStockCommand_WiringAndOutbox_SufficientStock(t *testing.T) {
	db := openTestDB(t)
	defer db.Close()

	repo := repository.NewPostgresRepository(db)
	svc := service.NewInventoryService(repo)

	productID := fmt.Sprintf("wiring-reserve-ok-%d", time.Now().UnixNano())
	seedInventory(t, db, productID, 10, 0, true)

	consumer, err := NewRabbitMQConsumer()
	if err != nil {
		t.Fatalf("failed to connect consumer: %v", err)
	}
	defer consumer.Close()
	if err := consumer.SetupOrderExchange(); err != nil {
		t.Fatalf("failed to set up order.exchange: %v", err)
	}

	outbox, err := consumer.StartOutboxPublisher(repo)
	if err != nil {
		t.Fatalf("failed to start outbox publisher: %v", err)
	}
	defer outbox.Stop()

	_, pubCh := openTestChannel(t)
	defer pubCh.Close()

	// Declare/bind the real command queue and the test's own observation
	// queue synchronously, before starting the consumer goroutine or
	// publishing — a topic exchange drops a message outright (doesn't
	// buffer it) if no queue is bound yet at publish time.
	declareTestQueue(t, pubCh, "inventory.reserve.queue", "order.exchange", "stock.reserve")
	testQueue := "test.stock.reserved." + productID
	declareTestQueue(t, pubCh, testQueue, "order.exchange", "stock.reserved")
	go consumer.ConsumeReserveStockCommands(svc)

	orderID := "order-" + productID
	command := models.ReserveStockCommand{
		OrderID: orderID,
		Items:   []models.ReserveStockItem{{ProductID: productID, Quantity: 3}},
	}
	body, _ := json.Marshal(command)
	if err := pubCh.Publish("order.exchange", "stock.reserve", false, false, amqp.Publishing{
		ContentType: "application/json",
		Body:        body,
	}); err != nil {
		t.Fatalf("failed to publish ReserveStockCommand: %v", err)
	}

	reserved := awaitCondition(10*time.Second, func() bool {
		var r int
		if err := db.QueryRow(`SELECT reserved FROM inventory_schema.inventory WHERE product_id = $1`, productID).Scan(&r); err != nil {
			return false
		}
		return r == 3
	})
	if !reserved {
		t.Fatalf("expected reserved quantity to reach 3 for product %s via the real ReserveStockCommand consumer", productID)
	}

	msg := awaitMessage(t, pubCh, testQueue, 10*time.Second)
	if msg == nil {
		t.Fatalf("expected a stock.reserved event published via the real Outbox for order %s", orderID)
	}
	var event models.StockReservedEvent
	if err := json.Unmarshal(msg.Body, &event); err != nil {
		t.Fatalf("failed to decode stock.reserved event: %v", err)
	}
	if event.OrderID != orderID || !event.Success {
		t.Fatalf("unexpected stock.reserved event: %+v", event)
	}
}

func TestReserveStockCommand_WiringAndOutbox_InsufficientStock(t *testing.T) {
	db := openTestDB(t)
	defer db.Close()

	repo := repository.NewPostgresRepository(db)
	svc := service.NewInventoryService(repo)

	productID := fmt.Sprintf("wiring-reserve-insufficient-%d", time.Now().UnixNano())
	seedInventory(t, db, productID, 2, 0, true)

	consumer, err := NewRabbitMQConsumer()
	if err != nil {
		t.Fatalf("failed to connect consumer: %v", err)
	}
	defer consumer.Close()
	if err := consumer.SetupOrderExchange(); err != nil {
		t.Fatalf("failed to set up order.exchange: %v", err)
	}

	outbox, err := consumer.StartOutboxPublisher(repo)
	if err != nil {
		t.Fatalf("failed to start outbox publisher: %v", err)
	}
	defer outbox.Stop()

	_, pubCh := openTestChannel(t)
	defer pubCh.Close()

	declareTestQueue(t, pubCh, "inventory.reserve.queue", "order.exchange", "stock.reserve")
	testQueue := "test.stock.insufficient." + productID
	declareTestQueue(t, pubCh, testQueue, "order.exchange", "stock.insufficient")
	go consumer.ConsumeReserveStockCommands(svc)

	orderID := "order-" + productID
	command := models.ReserveStockCommand{
		OrderID: orderID,
		Items:   []models.ReserveStockItem{{ProductID: productID, Quantity: 5}},
	}
	body, _ := json.Marshal(command)
	if err := pubCh.Publish("order.exchange", "stock.reserve", false, false, amqp.Publishing{
		ContentType: "application/json",
		Body:        body,
	}); err != nil {
		t.Fatalf("failed to publish ReserveStockCommand: %v", err)
	}

	msg := awaitMessage(t, pubCh, testQueue, 10*time.Second)
	if msg == nil {
		t.Fatalf("expected a stock.insufficient event published via the real Outbox for order %s", orderID)
	}
	var event models.StockInsufficientEvent
	if err := json.Unmarshal(msg.Body, &event); err != nil {
		t.Fatalf("failed to decode stock.insufficient event: %v", err)
	}
	if event.OrderID != orderID || event.ProductID != productID {
		t.Fatalf("unexpected stock.insufficient event: %+v", event)
	}

	var reserved int
	if err := db.QueryRow(`SELECT reserved FROM inventory_schema.inventory WHERE product_id = $1`, productID).Scan(&reserved); err != nil {
		t.Fatalf("failed to read back inventory row: %v", err)
	}
	if reserved != 0 {
		t.Fatalf("reserved stock should stay at 0 when reservation is rejected, got %d", reserved)
	}
}

func TestReleaseStockCommand_Wiring_ReleasesReservedStock(t *testing.T) {
	db := openTestDB(t)
	defer db.Close()

	repo := repository.NewPostgresRepository(db)
	svc := service.NewInventoryService(repo)

	productID := fmt.Sprintf("wiring-release-%d", time.Now().UnixNano())
	seedInventory(t, db, productID, 10, 5, true)

	consumer, err := NewRabbitMQConsumer()
	if err != nil {
		t.Fatalf("failed to connect consumer: %v", err)
	}
	defer consumer.Close()
	if err := consumer.SetupOrderExchange(); err != nil {
		t.Fatalf("failed to set up order.exchange: %v", err)
	}

	_, pubCh := openTestChannel(t)
	defer pubCh.Close()

	declareTestQueue(t, pubCh, "inventory.release.queue", "order.exchange", "stock.release")
	go consumer.ConsumeReleaseStockCommands(svc)

	command := models.ReleaseStockCommand{
		OrderID: "order-" + productID,
		Items:   []models.ReleaseStockItem{{ProductID: productID, Quantity: 5}},
	}
	body, _ := json.Marshal(command)
	if err := pubCh.Publish("order.exchange", "stock.release", false, false, amqp.Publishing{
		ContentType: "application/json",
		Body:        body,
	}); err != nil {
		t.Fatalf("failed to publish ReleaseStockCommand: %v", err)
	}

	released := awaitCondition(10*time.Second, func() bool {
		var r int
		if err := db.QueryRow(`SELECT reserved FROM inventory_schema.inventory WHERE product_id = $1`, productID).Scan(&r); err != nil {
			return false
		}
		return r == 0
	})
	if !released {
		t.Fatalf("expected reserved quantity to return to 0 for product %s via the real ReleaseStockCommand consumer", productID)
	}
}
