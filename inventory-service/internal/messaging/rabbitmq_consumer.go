package messaging

import (
	"encoding/json"
	"fmt"
	"inventory-service/internal/models"
	"inventory-service/internal/service"
	"inventory-service/pkg/config"
	"log"
	"sync"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

type RabbitMQConsumer struct {
    conn      *amqp.Connection
    cfg       *config.Config
    channels  []*amqp.Channel // Multiple channels
    mu        sync.Mutex
    wg        sync.WaitGroup
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

// setupExchange creates/verifies the exchange using a dedicated channel
func (r *RabbitMQConsumer) setupExchange() error {
    channel, err := r.createChannel()
    if err != nil {
        return err
    }
    defer channel.Close()
    
    // Create exchange
    err = channel.ExchangeDeclare(
        "order.exchange", // name
        "topic",          // type
        true,             // durable
        false,            // auto-deleted
        false,            // internal
        false,            // no-wait
        nil,              // arguments
    )
    if err != nil {
        return fmt.Errorf("failed to create exchange 'order.exchange': %w", err)
    }
    
    log.Println("Exchange 'order.exchange' (topic) created/verified")
    return nil
}

// setupQueue creates a queue using a dedicated channel
func (r *RabbitMQConsumer) setupQueue(channel *amqp.Channel, queueName, routingKey string) (amqp.Queue, error) {
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
        queue.Name,      // queue name
        routingKey,      // routing key
        "order.exchange", // exchange
        false,           // no-wait
        nil,             // arguments
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
    kafkaProducer *KafkaProducer,
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
    queue, err := r.setupQueue(channel, "inventory.reserve.queue", "stock.reserve")
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
        log.Printf("[RESERVE] Message received: %s", string(d.Body))

        var command models.ReserveStockCommand
        if err := json.Unmarshal(d.Body, &command); err != nil {
            log.Printf("[RESERVE] Error decoding message: %v", err)
            d.Nack(false, false) // Do not requeue - invalid message
            continue
        }

        log.Printf("[RESERVE] Processing ReserveStockCommand for order: %s", command.OrderID)

        orderId, success, insufficientEvent, err := inventoryService.ReserveStock(&command)

        if err != nil {
            log.Printf("[RESERVE] Error processing reservation for order %s: %v", command.OrderID, err)
            d.Nack(false, true) // Requeue to try again
            continue
        }

        // Publish result to Kafka
        if success {
            log.Printf("[RESERVE] Stock reserved for order: %s", orderId)

            reservedEvent := &models.StockReservedEvent{
                OrderID:   orderId,
                Success:   true,
                Message:   "stock reserved",
                Timestamp: time.Now(),
            }
            if err := kafkaProducer.PublishStockReservedOrder(reservedEvent); err != nil {
                log.Printf("[RESERVE] Failed to publish StockReservedEvent: %v", err)
                d.Nack(false, true) // Requeue to try again
                continue
            }
            log.Printf("[RESERVE] StockReservedEvent published for order: %s", orderId)
        } else {
            log.Printf("[RESERVE] Stock reservation failed for order: %s", orderId)

            // Publish insufficient-stock event
            if insufficientEvent != nil {
                log.Printf("[RESERVE] Publishing StockInsufficientEvent for order: %s, product: %s",
                    insufficientEvent.OrderID, insufficientEvent.ProductID)

                if err := kafkaProducer.PublishStockInsufficientOrder(insufficientEvent); err != nil {
                    log.Printf("[RESERVE] Failed to publish StockInsufficientEvent: %v", err)
                } else {
                    log.Printf("[RESERVE] StockInsufficientEvent published for order: %s",
                        insufficientEvent.OrderID)
                }
            }
        }

        // Confirm processing
        d.Ack(false)
        log.Printf("[RESERVE] Message processed for order: %s", command.OrderID)
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
    queue, err := r.setupQueue(channel, "inventory.release.queue", "stock.release")
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
        log.Printf("[RELEASE] Message received: %s", string(d.Body))

        var command models.ReleaseStockCommand
        if err := json.Unmarshal(d.Body, &command); err != nil {
            log.Printf("[RELEASE] Error decoding release command: %v", err)
            d.Nack(false, false) // Do not requeue - invalid message
            continue
        }

        log.Printf("[RELEASE] Processing ReleaseStockCommand for order: %s", command.OrderID)

        if err := inventoryService.ReleaseStock(&command); err != nil {
            log.Printf("[RELEASE] Failed to process release command: %v", err)
            d.Nack(false, true) // Requeue to try again
            continue
        }

        d.Ack(false)
        log.Printf("[RELEASE] Stock released for order: %s", command.OrderID)
    }
    
    return nil
}

// Start starts all consumers in separate goroutines
func (r *RabbitMQConsumer) Start(
    inventoryService service.InventoryService,
    kafkaProducer *KafkaProducer,
) {
    log.Println("Starting RabbitMQ consumers...")

    // First, create the exchange
    if err := r.setupExchange(); err != nil {
        log.Fatalf("Failed to create exchange: %v", err)
    }

    // Start the reserve consumer in a separate goroutine
    go func() {
        if err := r.ConsumeReserveStockCommands(inventoryService, kafkaProducer); err != nil {
            log.Printf("Error in reserve consumer: %v", err)
        }
    }()

    // Start the release consumer in a separate goroutine
    go func() {
        if err := r.ConsumeReleaseStockCommands(inventoryService); err != nil {
            log.Printf("Error in release consumer: %v", err)
        }
    }()

    log.Println("All RabbitMQ consumers have been started")
    
    // Wait for goroutines (keep the service running)
    r.wg.Wait()
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