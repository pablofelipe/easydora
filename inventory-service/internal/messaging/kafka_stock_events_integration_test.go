package messaging

import (
	"context"
	"encoding/json"
	"fmt"
	"inventory-service/internal/models"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/require"
)

// adminBroker is used only for operations that follow broker-advertised
// metadata (controller lookup, consumer group coordination): the
// docker-compose Kafka has two listeners — PLAINTEXT (advertised as
// "kafka:9092", for other containers) and PLAINTEXT_HOST (advertised as
// "localhost:29092", for the host machine). cfg.KafkaBrokers
// (localhost:9092, from .env) happens to work for a bare produce because
// KafkaProducer's Writer talks directly to that single connection without
// re-resolving a leader address, but any client that follows advertised
// metadata (topic admin, consumer groups) needs the listener that actually
// advertises a host-reachable address.
const adminBroker = "localhost:29092"

// ensureTopicExists creates the topic via the cluster controller if it
// doesn't exist yet. Needed because kafka.Writer (used by KafkaProducer)
// does not auto-create topics on write, and this test may run against a
// freshly started, empty Kafka broker.
func ensureTopicExists(t *testing.T, topic string) {
	t.Helper()

	conn, err := kafka.Dial("tcp", adminBroker)
	require.NoError(t, err)
	defer conn.Close()

	controller, err := conn.Controller()
	require.NoError(t, err)

	controllerConn, err := kafka.Dial("tcp", net.JoinHostPort(controller.Host, strconv.Itoa(controller.Port)))
	require.NoError(t, err)
	defer controllerConn.Close()

	err = controllerConn.CreateTopics(kafka.TopicConfig{
		Topic:             topic,
		NumPartitions:     1,
		ReplicationFactor: 1,
	})
	if err != nil && !strings.Contains(err.Error(), "already exists") {
		require.NoError(t, err)
	}
	// Give a newly created topic a moment to propagate before anything
	// tries to produce/consume against it.
	time.Sleep(500 * time.Millisecond)
}

// newTestReader opens a fresh reader positioned strictly after whatever is
// already in the topic, so each test only observes messages published after
// it starts regardless of what earlier runs left behind.
//
// kafka.LastOffset is deliberately NOT used as ReaderConfig.StartOffset:
// that field only accepts the sentinel values FirstOffset/LastOffset/0 (see
// its doc — "must be set to one of FirstOffset or LastOffset"); passing an
// arbitrary numeric offset there is silently ignored and the reader falls
// back to FirstOffset, replaying the whole topic. The concrete high-water
// offset must instead be applied via Reader.SetOffset after construction,
// which is documented to accept arbitrary numeric offsets.
//
// It reads partition 0 directly with no consumer group (GroupID left
// empty) — group coordination adds a join/rebalance round trip this test
// doesn't need, since nothing else is competing for these test-only topics.
func newTestReader(t *testing.T, topic string) *kafka.Reader {
	t.Helper()

	conn, err := kafka.DialLeader(context.Background(), "tcp", adminBroker, topic, 0)
	require.NoError(t, err)
	startOffset, err := conn.ReadLastOffset()
	require.NoError(t, err)
	require.NoError(t, conn.Close())

	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:   []string{adminBroker},
		Topic:     topic,
		Partition: 0,
	})
	require.NoError(t, reader.SetOffset(startOffset))
	return reader
}

func readOneMessage(t *testing.T, reader *kafka.Reader) kafka.Message {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	msg, err := reader.ReadMessage(ctx)
	require.NoError(t, err, "timed out waiting for a message on topic %s", reader.Config().Topic)
	return msg
}

// TestPublishStockReservedOrder_PublishesFullEvent proves that, today, the
// Kafka payload for a successful reservation is just the raw orderId bytes
// (not the rich models.StockReservedEvent struct that already exists but is
// never serialized). Consuming it back and trying to json.Unmarshal it into
// that struct fails, since a bare identifier like "order-123" isn't valid
// JSON on its own.
func TestPublishStockReservedOrder_PublishesFullEvent(t *testing.T) {
	ensureTopicExists(t, "stock-reserved")

	producer, err := NewKafkaProducer()
	require.NoError(t, err)
	defer producer.Close()

	reader := newTestReader(t, "stock-reserved")
	defer reader.Close()
	// Let the reader finish consumer-group join / initial offset
	// resolution before publishing, so the message isn't skipped as
	// "already past LastOffset".
	time.Sleep(1 * time.Second)

	orderId := fmt.Sprintf("order-reserved-%d", time.Now().UnixNano())
	require.NoError(t, producer.PublishStockReservedOrder(&models.StockReservedEvent{
		OrderID:   orderId,
		Success:   true,
		Message:   "stock reserved",
		Timestamp: time.Now(),
	}))

	msg := readOneMessage(t, reader)

	var event models.StockReservedEvent
	require.NoError(t, json.Unmarshal(msg.Value, &event),
		"payload should be the full StockReservedEvent JSON, not a raw orderId string: %q", string(msg.Value))
	require.Equal(t, orderId, event.OrderID)
	require.True(t, event.Success)
}

// TestPublishStockInsufficientOrder_PublishesFullEvent is the equivalent for
// the insufficient-stock path: today only orderId is published, discarding
// the productId/required/available fields already computed by the caller.
func TestPublishStockInsufficientOrder_PublishesFullEvent(t *testing.T) {
	ensureTopicExists(t, "stock-insufficient")

	producer, err := NewKafkaProducer()
	require.NoError(t, err)
	defer producer.Close()

	reader := newTestReader(t, "stock-insufficient")
	defer reader.Close()
	// Let the reader finish consumer-group join / initial offset
	// resolution before publishing, so the message isn't skipped as
	// "already past LastOffset".
	time.Sleep(1 * time.Second)

	orderId := fmt.Sprintf("order-insufficient-%d", time.Now().UnixNano())
	require.NoError(t, producer.PublishStockInsufficientOrder(&models.StockInsufficientEvent{
		OrderID:   orderId,
		ProductID: "prod-1",
		Required:  5,
		Available: 2,
		Timestamp: time.Now(),
	}))

	msg := readOneMessage(t, reader)

	var event models.StockInsufficientEvent
	require.NoError(t, json.Unmarshal(msg.Value, &event),
		"payload should be the full StockInsufficientEvent JSON, not a raw orderId string: %q", string(msg.Value))
	require.Equal(t, orderId, event.OrderID)
	require.Equal(t, "prod-1", event.ProductID, "productId should not be dropped from the wire payload")
	require.Equal(t, 5, event.Required, "required should not be dropped from the wire payload")
	require.Equal(t, 2, event.Available, "available should not be dropped from the wire payload")
}
