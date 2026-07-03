package messaging

import (
	"context"
	"encoding/json"
	"inventory-service/internal/models"
	"inventory-service/pkg/config"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/joho/godotenv"
	amqp "github.com/rabbitmq/amqp091-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestMain loads the same .env file main.go loads (RabbitMQ host/port/
// credentials), so this integration test talks to the real broker with the
// real production configuration instead of the package's hardcoded
// fallback defaults (which don't match the docker-compose password).
//
// KAFKA_BROKERS is forced to localhost:29092 (set before godotenv.Load, so
// the .env value doesn't override it — godotenv never overwrites a variable
// that's already set). .env's KAFKA_BROKERS=localhost:9092 maps to Kafka's
// PLAINTEXT listener, advertised internally as "kafka:9092" — unreachable
// from a test running natively on the host. Kafka's PLAINTEXT_HOST listener
// advertises "localhost:29092", which is what a host process needs for
// anything beyond a bare produce to an already-known topic (consumer group
// coordination, topic admin, or produce metadata once a topic exists all
// follow the advertised address).
func TestMain(m *testing.M) {
	if os.Getenv("KAFKA_BROKERS") == "" {
		os.Setenv("KAFKA_BROKERS", "localhost:29092")
	}
	for _, candidate := range []string{".env", "../../.env", "../../../.env"} {
		if _, err := os.Stat(candidate); err == nil {
			_ = godotenv.Load(candidate)
			break
		}
	}
	os.Exit(m.Run())
}

// releaseCommandJavaShape mirrors exactly what orders-service (Java) puts on
// the wire for a ReleaseStockCommand: Jackson's default bean serialization
// of ReleaseStockCommand{orderId, items[{productId, quantity}]}, with no
// custom @JsonProperty overrides. It intentionally does not reuse
// models.ReleaseStockCommand, since that Go struct is the side under test.
type releaseCommandJavaShape struct {
	OrderID string                    `json:"orderId"`
	Items   []releaseItemJavaShape    `json:"items"`
}

type releaseItemJavaShape struct {
	ProductID string `json:"productId"`
	Quantity  int    `json:"quantity"`
}

// mockReleaseInventoryService implements service.InventoryService, capturing
// every ReleaseStockCommand handed to it by the real RabbitMQ consumer. Only
// ReleaseStock is exercised by this test; the rest are unused no-ops.
type mockReleaseInventoryService struct {
	mu       sync.Mutex
	received []*models.ReleaseStockCommand
	done     chan struct{}
}

func newMockReleaseInventoryService() *mockReleaseInventoryService {
	return &mockReleaseInventoryService{done: make(chan struct{}, 8)}
}

func (m *mockReleaseInventoryService) CreateInventory(string, int) error { return nil }
func (m *mockReleaseInventoryService) GetInventory(string) (*models.Inventory, error) {
	return nil, nil
}
func (m *mockReleaseInventoryService) UpdateInventory(string, int) error { return nil }
func (m *mockReleaseInventoryService) ReserveStock(*models.ReserveStockCommand) (string, bool, *models.StockInsufficientEvent, error) {
	return "", false, nil, nil
}
func (m *mockReleaseInventoryService) ReleaseStock(command *models.ReleaseStockCommand) error {
	m.mu.Lock()
	m.received = append(m.received, command)
	m.mu.Unlock()
	m.done <- struct{}{}
	return nil
}
func (m *mockReleaseInventoryService) DeactivateProduct(string) error { return nil }
func (m *mockReleaseInventoryService) DeleteProduct(string) error     { return nil }

// TestReleaseStockCommand_OrderIdFromJavaPublisher is a real end-to-end
// integration test against a live RabbitMQ: it publishes exactly the JSON
// shape orders-service (Java) sends for stock.release, through the real
// "order.exchange", and drives it through the real
// ConsumeReleaseStockCommands consumer. Before the fix, ReleaseStockCommand
// decodes the orderId into an empty string because the Go struct tag is
// json:"order_id" (snake_case) while the wire field is "orderId" (camelCase).
func TestReleaseStockCommand_OrderIdFromJavaPublisher(t *testing.T) {
	consumer, err := NewRabbitMQConsumer()
	require.NoError(t, err, "requires a live RabbitMQ (docker-compose up rabbitmq)")
	defer consumer.Close()

	require.NoError(t, consumer.setupExchange())

	mockSvc := newMockReleaseInventoryService()
	go func() {
		_ = consumer.ConsumeReleaseStockCommands(mockSvc)
	}()

	// Give the consumer time to declare/bind inventory.release.queue before
	// the message is published, so it isn't missed.
	time.Sleep(1 * time.Second)

	publishRawReleaseCommand(t, releaseCommandJavaShape{
		OrderID: "order-99",
		Items:   []releaseItemJavaShape{{ProductID: "prod-1", Quantity: 3}},
	})

	select {
	case <-mockSvc.done:
	case <-time.After(10 * time.Second):
		t.Fatal("timed out waiting for ReleaseStock to be invoked by the consumer")
	}

	mockSvc.mu.Lock()
	defer mockSvc.mu.Unlock()
	require.Len(t, mockSvc.received, 1)
	assert.Equal(t, "order-99", mockSvc.received[0].OrderID,
		"OrderID decoded from the Java publisher's JSON should be populated, not empty")
}

func publishRawReleaseCommand(t *testing.T, payload releaseCommandJavaShape) {
	t.Helper()

	body, err := json.Marshal(payload)
	require.NoError(t, err)

	cfg := config.Load()
	conn, err := amqp.Dial(cfg.RabbitMQURL)
	require.NoError(t, err)
	defer conn.Close()

	ch, err := conn.Channel()
	require.NoError(t, err)
	defer ch.Close()

	err = ch.PublishWithContext(context.Background(), "order.exchange", "stock.release", false, false, amqp.Publishing{
		ContentType: "application/json",
		Body:        body,
	})
	require.NoError(t, err)
}

