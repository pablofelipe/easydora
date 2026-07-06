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

// These tests drive the real product.exchange (product.created/updated/
// deleted) and the real Consume*Events consumers against real Postgres and
// RabbitMQ (CI Phase 2 service containers). The pure decision logic
// (applyProductCreatedEvent/Updated/Deleted) already has broker-agnostic
// unit coverage in product_events_behavior_test.go — these prove the
// RabbitMQ wiring around it actually works end to end.

func TestProductCreatedEvent_Wiring_CreatesInventoryRecord(t *testing.T) {
	db := openTestDB(t)
	defer db.Close()

	repo := repository.NewPostgresRepository(db)
	svc := service.NewInventoryService(repo)

	productID := fmt.Sprintf("wiring-product-created-%d", time.Now().UnixNano())

	consumer, err := NewRabbitMQConsumer()
	if err != nil {
		t.Fatalf("failed to connect consumer: %v", err)
	}
	defer consumer.Close()
	if err := consumer.SetupProductExchange(); err != nil {
		t.Fatalf("failed to set up product.exchange: %v", err)
	}

	_, pubCh := openTestChannel(t)
	defer pubCh.Close()

	// Declare/bind the real production queue synchronously, from the test's
	// own channel, before starting the consumer goroutine below or
	// publishing anything. Without this, publishing can race the
	// goroutine's own queue setup — a topic exchange drops a message
	// outright if no queue is bound yet, it doesn't buffer it — so a
	// publish that lands before the queue exists is silently lost, not
	// delayed.
	declareTestQueue(t, pubCh, "inventory.product.created.queue", "product.exchange", "product.created")
	go consumer.ConsumeProductCreatedEvents(svc)

	event := models.ProductCreatedEvent{
		ProductID:    productID,
		ProductName:  "Wiring Widget",
		SellerID:     "seller-wiring",
		Price:        9.99,
		InitialStock: 15,
		CreatedAt:    time.Now().Format(time.RFC3339),
	}
	body, _ := json.Marshal(event)
	if err := pubCh.Publish("product.exchange", "product.created", false, false, amqp.Publishing{
		ContentType: "application/json",
		Body:        body,
	}); err != nil {
		t.Fatalf("failed to publish product.created: %v", err)
	}

	created := awaitCondition(10*time.Second, func() bool {
		var quantity int
		err := db.QueryRow(`SELECT quantity FROM inventory_schema.inventory WHERE product_id = $1`, productID).Scan(&quantity)
		return err == nil && quantity == 15
	})
	if !created {
		t.Fatalf("expected an inventory record with quantity 15 for product %s via the real product.created consumer", productID)
	}
}

func TestProductUpdatedEvent_Wiring_DeactivatesInventoryRecord(t *testing.T) {
	db := openTestDB(t)
	defer db.Close()

	repo := repository.NewPostgresRepository(db)
	svc := service.NewInventoryService(repo)

	productID := fmt.Sprintf("wiring-product-updated-%d", time.Now().UnixNano())
	seedInventory(t, db, productID, 5, 0, true)

	consumer, err := NewRabbitMQConsumer()
	if err != nil {
		t.Fatalf("failed to connect consumer: %v", err)
	}
	defer consumer.Close()
	if err := consumer.SetupProductExchange(); err != nil {
		t.Fatalf("failed to set up product.exchange: %v", err)
	}

	_, pubCh := openTestChannel(t)
	defer pubCh.Close()

	declareTestQueue(t, pubCh, "inventory.product.updated.queue", "product.exchange", "product.updated")
	go consumer.ConsumeProductUpdatedEvents(svc)

	event := models.ProductUpdatedEvent{
		ProductID:   productID,
		ProductName: "Wiring Widget",
		Price:       9.99,
		Active:      false,
		UpdatedAt:   time.Now().Format(time.RFC3339),
	}
	body, _ := json.Marshal(event)
	if err := pubCh.Publish("product.exchange", "product.updated", false, false, amqp.Publishing{
		ContentType: "application/json",
		Body:        body,
	}); err != nil {
		t.Fatalf("failed to publish product.updated: %v", err)
	}

	deactivated := awaitCondition(10*time.Second, func() bool {
		var available bool
		err := db.QueryRow(`SELECT available FROM inventory_schema.inventory WHERE product_id = $1`, productID).Scan(&available)
		return err == nil && !available
	})
	if !deactivated {
		t.Fatalf("expected inventory record for product %s to be deactivated via the real product.updated consumer", productID)
	}
}

func TestProductDeletedEvent_Wiring_MarksInventoryDeleted(t *testing.T) {
	db := openTestDB(t)
	defer db.Close()

	repo := repository.NewPostgresRepository(db)
	svc := service.NewInventoryService(repo)

	productID := fmt.Sprintf("wiring-product-deleted-%d", time.Now().UnixNano())
	seedInventory(t, db, productID, 5, 0, true)

	consumer, err := NewRabbitMQConsumer()
	if err != nil {
		t.Fatalf("failed to connect consumer: %v", err)
	}
	defer consumer.Close()
	if err := consumer.SetupProductExchange(); err != nil {
		t.Fatalf("failed to set up product.exchange: %v", err)
	}

	_, pubCh := openTestChannel(t)
	defer pubCh.Close()

	declareTestQueue(t, pubCh, "inventory.product.deleted.queue", "product.exchange", "product.deleted")
	go consumer.ConsumeProductDeletedEvents(svc)

	event := models.ProductDeletedEvent{
		ProductID: productID,
		DeletedAt: time.Now().Format(time.RFC3339),
	}
	body, _ := json.Marshal(event)
	if err := pubCh.Publish("product.exchange", "product.deleted", false, false, amqp.Publishing{
		ContentType: "application/json",
		Body:        body,
	}); err != nil {
		t.Fatalf("failed to publish product.deleted: %v", err)
	}

	deleted := awaitCondition(10*time.Second, func() bool {
		var isDeleted bool
		err := db.QueryRow(`SELECT deleted FROM inventory_schema.inventory WHERE product_id = $1`, productID).Scan(&isDeleted)
		return err == nil && isDeleted
	})
	if !deleted {
		t.Fatalf("expected inventory record for product %s to be marked deleted via the real product.deleted consumer", productID)
	}
}
