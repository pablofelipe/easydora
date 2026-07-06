//go:build integration

package messaging

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"

	"inventory-service/pkg/database"
)

// openTestDB connects to the real Postgres (CI Phase 2 service container)
// and makes sure inventory_schema exists by replaying the same init.sql
// main.go runs on boot — idempotent (CREATE ... IF NOT EXISTS), safe to run
// once per test.
func openTestDB(t *testing.T) *sql.DB {
	t.Helper()
	db, err := database.InitPostgres()
	if err != nil {
		t.Fatalf("failed to connect to Postgres: %v", err)
	}
	ensureSchema(t, db)
	return db
}

func ensureSchema(t *testing.T, db *sql.DB) {
	t.Helper()
	_, thisFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("failed to resolve current test file path")
	}
	scriptPath := filepath.Join(filepath.Dir(thisFile), "..", "..", "scripts", "init.sql")
	content, err := os.ReadFile(scriptPath)
	if err != nil {
		t.Fatalf("failed to read init.sql: %v", err)
	}
	if _, err := db.Exec(string(content)); err != nil {
		t.Fatalf("failed to apply schema: %v", err)
	}
}

func seedInventory(t *testing.T, db *sql.DB, productID string, quantity, reserved int, available bool) {
	t.Helper()
	_, err := db.Exec(`
		INSERT INTO inventory_schema.inventory (product_id, quantity, reserved, available, deleted)
		VALUES ($1, $2, $3, $4, false)
		ON CONFLICT (product_id) DO UPDATE SET
			quantity = $2, reserved = $3, available = $4, deleted = false, updated_at = NOW()`,
		productID, quantity, reserved, available)
	if err != nil {
		t.Fatalf("failed to seed inventory row for %s: %v", productID, err)
	}
}

// openTestChannel opens a dedicated RabbitMQ connection/channel for the
// test itself to act as the "other side" of a wiring test (publishing a
// command/event the way an upstream service would, or observing what the
// service under test publishes) — independent of the RabbitMQConsumer
// instance under test.
func openTestChannel(t *testing.T) (*amqp.Connection, *amqp.Channel) {
	t.Helper()
	conn, err := amqp.Dial(testAmqpURL())
	if err != nil {
		t.Fatalf("failed to connect to RabbitMQ: %v", err)
	}
	ch, err := conn.Channel()
	if err != nil {
		conn.Close()
		t.Fatalf("failed to open channel: %v", err)
	}
	return conn, ch
}

func testAmqpURL() string {
	host := getenvDefault("RABBITMQ_HOST", "localhost")
	port := getenvDefault("RABBITMQ_PORT", "5672")
	user := getenvDefault("RABBITMQ_USER", "admin")
	password := getenvDefault("RABBITMQ_PASSWORD", "PWD")
	return fmt.Sprintf("amqp://%s:%s@%s:%s/", user, password, host, port)
}

func getenvDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func declareTestQueue(t *testing.T, ch *amqp.Channel, queueName, exchange, routingKey string) {
	t.Helper()
	if _, err := ch.QueueDeclare(queueName, true, false, false, false, nil); err != nil {
		t.Fatalf("failed to declare test queue %s: %v", queueName, err)
	}
	if err := ch.QueueBind(queueName, routingKey, exchange, false, nil); err != nil {
		t.Fatalf("failed to bind test queue %s: %v", queueName, err)
	}
	drainQueue(ch, queueName)
}

func drainQueue(ch *amqp.Channel, queueName string) {
	for {
		_, ok, err := ch.Get(queueName, true)
		if err != nil || !ok {
			return
		}
	}
}

func awaitMessage(t *testing.T, ch *amqp.Channel, queueName string, timeout time.Duration) *amqp.Delivery {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		msg, ok, err := ch.Get(queueName, true)
		if err != nil {
			t.Fatalf("failed to poll queue %s: %v", queueName, err)
		}
		if ok {
			return &msg
		}
		time.Sleep(250 * time.Millisecond)
	}
	return nil
}

func awaitCondition(timeout time.Duration, check func() bool) bool {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if check() {
			return true
		}
		time.Sleep(250 * time.Millisecond)
	}
	return false
}
