package messaging

import (
	"context"
	"encoding/json"
	"fmt"
	"easydora/correlation-commons"
	"inventory-service/internal/models"
	"inventory-service/internal/service"
	"inventory-service/pkg/config"
	"log"
	"os"
	"sync"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

var consumerLogger = correlation.NewLogger(os.Stdout, "inventory-service")

// contextFromDelivery builds a context carrying the inbound message's
// CorrelationId (reused, or freshly generated if the publisher didn't set
// one) and its MessageId, so every log line and downstream call made while
// handling this delivery can be tied back to it.
func contextFromDelivery(d amqp.Delivery) context.Context {
	ctx := context.Background()
	correlationID := d.CorrelationId
	if correlationID == "" {
		correlationID = correlation.NewID()
	}
	ctx = correlation.WithCorrelationID(ctx, correlationID)
	ctx = correlation.WithMessageID(ctx, d.MessageId)
	return ctx
}

type RabbitMQConsumer struct {
	conn     *amqp.Connection
	cfg      *config.Config
	channels []*amqp.Channel // Multiple channels
	mu       sync.Mutex
	wg       sync.WaitGroup
}

func NewRabbitMQConsumer() (*RabbitMQConsumer, error) {
	cfg := config.Load()

	// Try to connect with retry
	var conn *amqp.Connection
	var err error

	maxRetries := 10
	for i := 0; i < maxRetries; i++ {
		conn, err = amqp.Dial(cfg.RabbitMQURL)
		if err != nil {
			log.Printf("Attempt %d/%d - RabbitMQ is not ready: %v", i+1, maxRetries, err)
			time.Sleep(3 * time.Second)
			continue
		}
		log.Println("Connected to RabbitMQ")
		break
	}

	if err != nil {
		return nil, fmt.Errorf("failed to connect to RabbitMQ after %d attempts: %w", maxRetries, err)
	}

	return &RabbitMQConsumer{
		conn: conn,
		cfg:  cfg,
	}, nil
}

// createChannel creates a new channel for exclusive use
func (r *RabbitMQConsumer) createChannel() (*amqp.Channel, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	channel, err := r.conn.Channel()
	if err != nil {
		return nil, fmt.Errorf("failed to create channel: %w", err)
	}

	// Configure QoS
	err = channel.Qos(
		1,     // prefetch count
		0,     // prefetch size
		false, // global
	)
	if err != nil {
		channel.Close()
		return nil, fmt.Errorf("failed to configure QoS: %w", err)
	}

	r.channels = append(r.channels, channel)
	return channel, nil
}

// setupExchange creates/verifies a topic exchange using a dedicated channel
func (r *RabbitMQConsumer) setupExchange(exchangeName string) error {
	channel, err := r.createChannel()
	if err != nil {
		return err
	}
	defer channel.Close()

	err = channel.ExchangeDeclare(
		exchangeName, // name
		"topic",      // type
		true,         // durable
		false,        // auto-deleted
		false,        // internal
		false,        // no-wait
		nil,          // arguments
	)
	if err != nil {
		return fmt.Errorf("failed to create exchange '%s': %w", exchangeName, err)
	}

	log.Printf("Exchange '%s' (topic) created/verified", exchangeName)
	return nil
}

// setupQueue creates a queue using a dedicated channel and binds it to the given exchange/routing key
func (r *RabbitMQConsumer) setupQueue(channel *amqp.Channel, queueName, routingKey, exchangeName string) (amqp.Queue, error) {
	// Declare the queue
	queue, err := channel.QueueDeclare(
		queueName, // name
		true,      // durable
		false,     // delete when unused
		false,     // exclusive
		false,     // no-wait
		nil,       // arguments
	)
	if err != nil {
		return amqp.Queue{}, fmt.Errorf("failed to declare queue '%s': %w", queueName, err)
	}
	log.Printf("Queue '%s' created/verified", queueName)

	// Bind the queue to the exchange
	err = channel.QueueBind(
		queue.Name,   // queue name
		routingKey,   // routing key
		exchangeName, // exchange
		false,        // no-wait
		nil,          // arguments
	)
	if err != nil {
		return amqp.Queue{}, fmt.Errorf("failed to bind queue '%s' with routing key '%s': %w",
			queueName, routingKey, err)
	}
	log.Printf("Queue '%s' bound with routing key '%s'", queueName, routingKey)

	return queue, nil
}

func (r *RabbitMQConsumer) ConsumeReserveStockCommands(
	inventoryService service.InventoryService,
) error {
	r.wg.Add(1)
	defer r.wg.Done()

	// Create a dedicated channel for this consumer
	channel, err := r.createChannel()
	if err != nil {
		return fmt.Errorf("failed to create channel for reserve consumer: %w", err)
	}
	defer channel.Close()

	// Configure the reserve queue
	queue, err := r.setupQueue(channel, "inventory.reserve.queue", "stock.reserve", "order.exchange")
	if err != nil {
		return fmt.Errorf("failed to configure reserve queue: %w", err)
	}

	// Start consumer
	msgs, err := channel.Consume(
		queue.Name, // queue
		"",         // consumer
		false,      // auto-ack (manual ack)
		false,      // exclusive
		false,      // no-local
		false,      // no-wait
		nil,        // args
	)
	if err != nil {
		return fmt.Errorf("failed to register reserve consumer: %w", err)
	}

	log.Printf("Waiting for ReserveStockCommand on queue: %s (routing key: stock.reserve)", queue.Name)

	// Process messages
	for d := range msgs {
		ctx := contextFromDelivery(d)
		correlation.Info(consumerLogger, ctx, "message received", "event", "stock.reserve", "aggregateId", "")

		var command models.ReserveStockCommand
		if err := json.Unmarshal(d.Body, &command); err != nil {
			log.Printf("[RESERVE] Error decoding message: %v", err)
			d.Nack(false, false) // Do not requeue - invalid message
			continue
		}

		correlation.Info(consumerLogger, ctx, "processing ReserveStockCommand", "event", "stock.reserve", "aggregateId", command.OrderID)

		// ReserveStock writes the stock.reserved/stock.insufficient outbox
		// event atomically with the reservation itself (Outbox Pattern,
		// ADR-0007) — by the time this returns without error, the event
		// is already durably recorded and will be published by the
		// outbox poller. This consumer only needs to Ack/Nack based on
		// whether the reservation attempt itself succeeded.
		orderId, success, insufficientEvent, err := inventoryService.ReserveStock(ctx, &command)

		if err != nil {
			log.Printf("[RESERVE] Error processing reservation for order %s: %v", command.OrderID, err)
			d.Nack(false, true) // Requeue to try again
			continue
		}

		if success {
			correlation.Info(consumerLogger, ctx, "stock reserved", "event", "stock.reserved", "aggregateId", orderId)
		} else {
			correlation.Info(consumerLogger, ctx, "stock reservation failed", "event", "stock.insufficient", "aggregateId", orderId, "productId", insufficientEvent.ProductID)
		}

		d.Ack(false)
	}

	return nil
}

func (r *RabbitMQConsumer) ConsumeReleaseStockCommands(inventoryService service.InventoryService) error {
	r.wg.Add(1)
	defer r.wg.Done()

	// Create a dedicated channel for this consumer
	channel, err := r.createChannel()
	if err != nil {
		return fmt.Errorf("failed to create channel for release consumer: %w", err)
	}
	defer channel.Close()

	// Configure the release queue
	queue, err := r.setupQueue(channel, "inventory.release.queue", "stock.release", "order.exchange")
	if err != nil {
		return fmt.Errorf("failed to configure release queue: %w", err)
	}

	// Start consumer
	msgs, err := channel.Consume(
		queue.Name, // queue
		"",         // consumer
		false,      // auto-ack (manual ack)
		false,      // exclusive
		false,      // no-local
		false,      // no-wait
		nil,        // args
	)
	if err != nil {
		return fmt.Errorf("failed to register release consumer: %w", err)
	}

	log.Printf("Waiting for ReleaseStockCommand on queue: %s (routing key: stock.release)", queue.Name)

	// Process messages
	for d := range msgs {
		ctx := contextFromDelivery(d)

		var command models.ReleaseStockCommand
		if err := json.Unmarshal(d.Body, &command); err != nil {
			log.Printf("[RELEASE] Error decoding release command: %v", err)
			d.Nack(false, false) // Do not requeue - invalid message
			continue
		}

		correlation.Info(consumerLogger, ctx, "processing ReleaseStockCommand", "event", "stock.release", "aggregateId", command.OrderID)

		if err := inventoryService.ReleaseStock(ctx, &command); err != nil {
			log.Printf("[RELEASE] Failed to process release command: %v", err)
			d.Nack(false, true) // Requeue to try again
			continue
		}

		d.Ack(false)
		correlation.Info(consumerLogger, ctx, "stock released", "event", "stock.released", "aggregateId", command.OrderID)
	}

	return nil
}

// SetupOrderExchange creates/verifies the order.exchange topic exchange
// used for stock reserve/release commands.
func (r *RabbitMQConsumer) SetupOrderExchange() error {
	return r.setupExchange("order.exchange")
}

// SetupProductExchange creates/verifies the product.exchange topic exchange
// (ADR-0007) used for product.created/updated/deleted domain events.
func (r *RabbitMQConsumer) SetupProductExchange() error {
	return r.setupExchange("product.exchange")
}

func (r *RabbitMQConsumer) consumeProductEvent(
	queueName, routingKey string,
	handle func(body []byte) error,
) error {
	r.wg.Add(1)
	defer r.wg.Done()

	channel, err := r.createChannel()
	if err != nil {
		return fmt.Errorf("failed to create channel for %s consumer: %w", routingKey, err)
	}
	defer channel.Close()

	queue, err := r.setupQueue(channel, queueName, routingKey, "product.exchange")
	if err != nil {
		return fmt.Errorf("failed to configure %s queue: %w", routingKey, err)
	}

	msgs, err := channel.Consume(
		queue.Name, // queue
		"",         // consumer
		false,      // auto-ack (manual ack)
		false,      // exclusive
		false,      // no-local
		false,      // no-wait
		nil,        // args
	)
	if err != nil {
		return fmt.Errorf("failed to register %s consumer: %w", routingKey, err)
	}

	log.Printf("Waiting for %s on queue: %s (routing key: %s)", routingKey, queue.Name, routingKey)

	for d := range msgs {
		ctx := contextFromDelivery(d)
		correlation.Info(consumerLogger, ctx, "message received", "event", routingKey, "aggregateId", "")

		if err := handle(d.Body); err != nil {
			log.Printf("[%s] Error decoding message: %v", routingKey, err)
			d.Nack(false, false) // invalid message - do not requeue
			continue
		}

		d.Ack(false)
		correlation.Info(consumerLogger, ctx, "message processed", "event", routingKey, "aggregateId", "")
	}

	return nil
}

// ConsumeProductCreatedEvents consumes product.created events (ADR-0007)
// and applies the already-tested applyProductCreatedEvent decision
// (see product_events_behavior_test.go).
func (r *RabbitMQConsumer) ConsumeProductCreatedEvents(inventoryService service.InventoryService) error {
	return r.consumeProductEvent("inventory.product.created.queue", "product.created", func(body []byte) error {
		var event models.ProductCreatedEvent
		if err := json.Unmarshal(body, &event); err != nil {
			return err
		}
		applyProductCreatedEvent(event, inventoryService)
		return nil
	})
}

// ConsumeProductUpdatedEvents consumes product.updated events (ADR-0007)
// and applies the already-tested applyProductUpdatedEvent decision
// (see product_events_behavior_test.go).
func (r *RabbitMQConsumer) ConsumeProductUpdatedEvents(inventoryService service.InventoryService) error {
	return r.consumeProductEvent("inventory.product.updated.queue", "product.updated", func(body []byte) error {
		var event models.ProductUpdatedEvent
		if err := json.Unmarshal(body, &event); err != nil {
			return err
		}
		applyProductUpdatedEvent(event, inventoryService)
		return nil
	})
}

// ConsumeProductDeletedEvents consumes product.deleted events (ADR-0007)
// and applies the already-tested applyProductDeletedEvent decision
// (see product_events_behavior_test.go).
func (r *RabbitMQConsumer) ConsumeProductDeletedEvents(inventoryService service.InventoryService) error {
	return r.consumeProductEvent("inventory.product.deleted.queue", "product.deleted", func(body []byte) error {
		var event models.ProductDeletedEvent
		if err := json.Unmarshal(body, &event); err != nil {
			return err
		}
		applyProductDeletedEvent(event, inventoryService)
		return nil
	})
}

func (r *RabbitMQConsumer) Close() {
	r.mu.Lock()
	defer r.mu.Unlock()

	// Close all channels
	for _, channel := range r.channels {
		if channel != nil {
			channel.Close()
		}
	}

	// Close connection
	if r.conn != nil {
		r.conn.Close()
	}

	log.Println("RabbitMQ connection closed")
}
