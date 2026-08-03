//go:build integration

package messaging

import (
	"database/sql"
	"easydora/correlation-commons"
	"fmt"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"

	"inventory-service/internal/repository"
)

// TestOutboxPublisher_DoesNotMarkPublishedWhenExchangeMissing proves the gap
// found in the RabbitMQ resilience investigation alongside the topology
// redeclaration bug: publishPending used to call the fire-and-forget
// Publish(), which returns nil even when the target exchange does not
// exist -- the broker's rejection arrives asynchronously and closes the
// channel, but by then MarkOutboxEventPublished had already run, silently
// losing the event despite the file's own doc comment claiming
// "at-least-once delivery, never lost". Publisher confirms close this: a
// row must only be marked published once the broker actually acknowledges
// it, not merely once the client-side call returns without error.
func TestOutboxPublisher_DoesNotMarkPublishedWhenExchangeMissing(t *testing.T) {
	db := openTestDB(t)
	defer db.Close()

	repo := repository.NewPostgresRepository(db)

	consumer, err := NewRabbitMQConsumer()
	if err != nil {
		t.Fatalf("failed to connect consumer: %v", err)
	}
	defer consumer.Close()

	// A name guaranteed to never exist on this broker -- no test code ever
	// declares it, mirroring an outbox row that targets an exchange the
	// broker lost (ADR-0040: no PersistentVolume) and has not redeclared
	// yet.
	exchangeName := fmt.Sprintf("test.missing.exchange.%d", time.Now().UnixNano())

	correlationID := fmt.Sprintf("outbox-confirm-test-%d", time.Now().UnixNano())
	payload := correlation.WrapOutboxPayload(correlationID, "", "", `{"hello":"world"}`)

	var eventID int64
	if err := db.QueryRow(
		`INSERT INTO inventory_schema.outbox_events (exchange, routing_key, payload) VALUES ($1, $2, $3) RETURNING id`,
		exchangeName, "test.routing.key", payload,
	).Scan(&eventID); err != nil {
		t.Fatalf("failed to seed outbox row: %v", err)
	}
	// This row targets an exchange that will never exist, so it can never
	// be published -- unlike every other test in this package, nothing
	// will ever drain it. Deleting it here (not just asserting on it)
	// keeps it from poisoning every later poll cycle -- both this test
	// binary's own later tests and the live inventory-service container
	// polling the same shared table -- with a permanently-unpublishable
	// row that closes the publisher's channel on every attempt (see the
	// fix in ensureChannel/publishPending: one bad row must not be able to
	// block the rest of a batch, but it still wastes a full retry cycle
	// every 5s until removed).
	defer db.Exec(`DELETE FROM inventory_schema.outbox_events WHERE id = $1`, eventID)

	outbox, err := consumer.StartOutboxPublisher(repo)
	if err != nil {
		t.Fatalf("failed to start outbox publisher: %v", err)
	}
	defer outbox.Stop()

	publishedAt := func() sql.NullTime {
		var ts sql.NullTime
		if err := db.QueryRow(`SELECT published_at FROM inventory_schema.outbox_events WHERE id = $1`, eventID).Scan(&ts); err != nil {
			t.Fatalf("failed to read outbox row: %v", err)
		}
		return ts
	}

	// Give the publisher several poll ticks against an exchange that will
	// never exist in this test -- long enough that a fire-and-forget bug
	// would already have marked the row published.
	time.Sleep(2*outboxPollInterval + 2*time.Second)

	if ts := publishedAt(); ts.Valid {
		t.Fatal("outbox event was marked published even though its target exchange never existed")
	}
}

// TestOutboxPublisher_PublishesMessagesAsPersistent proves an outbox event
// still reaches a consumer after a broker restart, not just that the
// broker acknowledged the publish. ADR-0041's benchmark found 2 of 207
// broker-acknowledged messages lost under a hard container kill, on a
// durable queue -- the root cause is that amqp.Publishing{} leaves
// DeliveryMode at its zero value, which amqp091-go treats as Transient
// regardless of the queue's own durability (see amqp091-go's
// types.go: "Transient (0 or 1) or Persistent (2)"). A durable queue
// holding a transient message does not persist that message to disk, so a
// broker crash/restart can lose it even after a positive publisher
// confirm.
func TestOutboxPublisher_PublishesMessagesAsPersistent(t *testing.T) {
	db := openTestDB(t)
	defer db.Close()

	repo := repository.NewPostgresRepository(db)

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

	testQueue := fmt.Sprintf("test.persistence.%d", time.Now().UnixNano())
	declareTestQueue(t, pubCh, testQueue, "order.exchange", "test.persistence")

	correlationID := fmt.Sprintf("persistence-test-%d", time.Now().UnixNano())
	payload := correlation.WrapOutboxPayload(correlationID, "", "", `{"hello":"world"}`)
	if _, err := db.Exec(
		`INSERT INTO inventory_schema.outbox_events (exchange, routing_key, payload) VALUES ($1, $2, $3)`,
		"order.exchange", "test.persistence", payload,
	); err != nil {
		t.Fatalf("failed to seed outbox row: %v", err)
	}

	outbox, err := consumer.StartOutboxPublisher(repo)
	if err != nil {
		t.Fatalf("failed to start outbox publisher: %v", err)
	}
	defer outbox.Stop()

	msg := awaitMessage(t, pubCh, testQueue, 10*time.Second)
	if msg == nil {
		t.Fatal("expected the outbox publisher to deliver the seeded event")
	}
	if msg.DeliveryMode != amqp.Persistent {
		t.Fatalf("expected DeliveryMode to be Persistent (%d), got %d -- a durable queue does not protect a transient message from loss on broker restart", amqp.Persistent, msg.DeliveryMode)
	}
}
