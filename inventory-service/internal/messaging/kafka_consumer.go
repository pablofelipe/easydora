package messaging

import (
	"context"
	"encoding/json"
	"fmt"
	"inventory-service/internal/models"
	"inventory-service/internal/service"
	"inventory-service/pkg/config"
	"log"
	"strings"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"
)

type KafkaConsumer struct {
    readerCreated *kafka.Reader
    readerUpdated *kafka.Reader
    readerDeleted *kafka.Reader
    cfg           *config.Config
    wg            sync.WaitGroup
    mu            sync.Mutex
    running       bool
}

func NewKafkaConsumer() (*KafkaConsumer, error) {
    cfg := config.Load()
    
    // Validate configuration
    if cfg.KafkaBrokers == "" {
        return nil, fmt.Errorf("KafkaBrokers not configured")
    }
    
    log.Printf("Configuring Kafka Consumer for: %s", cfg.KafkaBrokers)
    
    k := &KafkaConsumer{
        cfg: cfg,
    }
    
    // Initialize readers
    if err := k.initializeReaders(); err != nil {
        return nil, fmt.Errorf("failed to initialize readers: %w", err)
    }
    
    return k, nil
}

func (k *KafkaConsumer) createReader(topic string) *kafka.Reader {
    return kafka.NewReader(kafka.ReaderConfig{
        Brokers:        []string{k.cfg.KafkaBrokers},
        Topic:          topic,
        GroupID:        "inventory-service-group",
        GroupTopics:    []string{topic},
        MinBytes:       1,
        MaxBytes:       10e6,
        MaxWait:        10 * time.Second,
        StartOffset:    kafka.FirstOffset,
        CommitInterval: 0,
        ReadBackoffMin: 100 * time.Millisecond,
        ReadBackoffMax: 1 * time.Second,
        Logger:         kafka.LoggerFunc(func(s string, i ...interface{}) {
            log.Printf("[KAFKA] "+s, i...)
        }),
        ErrorLogger:    kafka.LoggerFunc(func(s string, i ...interface{}) {
            log.Printf("[KAFKA-ERROR] "+s, i...)
        }),
        HeartbeatInterval: 3 * time.Second,
        SessionTimeout:    30 * time.Second,
        RetentionTime:     24 * time.Hour,
    })
}

func (k *KafkaConsumer) initializeReaders() error {

    maxRetries := 3
    var lastErr error
    
    for i := 0; i < maxRetries; i++ {
        log.Printf("Attempt %d/%d to connect to Kafka...", i+1, maxRetries)

        if err := k.testKafkaConnection(); err != nil {
            lastErr = err
            log.Printf("Failed to connect to Kafka: %v", err)
            time.Sleep(2 * time.Second)
            continue
        }
        
        k.readerCreated = k.createReader("product.created")
        k.readerUpdated = k.createReader("product.updated")
        k.readerDeleted = k.createReader("product.deleted")
        
        log.Println("All Kafka readers created successfully")
        return nil
    }
    
    return fmt.Errorf("failed to connect to Kafka after %d attempts: %v", maxRetries, lastErr)
}

func (k *KafkaConsumer) testKafkaConnection() error {
    conn, err := kafka.Dial("tcp", k.cfg.KafkaBrokers)
    if err != nil {
        return fmt.Errorf("failed to connect to Kafka: %w", err)
    }
    defer conn.Close()
    
    _, err = conn.ReadPartitions()
    if err != nil {
        return fmt.Errorf("failed to read partitions: %w", err)
    }
    
    return nil
}

func (k *KafkaConsumer) StartConsuming(inventoryService service.InventoryService) {
    log.Println("Starting Kafka consumers...")
    
    k.mu.Lock()
    k.running = true
    k.mu.Unlock()
    
    // Consume creation events
    k.wg.Add(1)
    go func() {
        defer k.wg.Done()
        k.consumeWithRetry("product.created", k.readerCreated, 
            k.handleProductCreated, inventoryService)
    }()
    
    // Consume update events
    k.wg.Add(1)
    go func() {
        defer k.wg.Done()
        k.consumeWithRetry("product.updated", k.readerUpdated,
            k.handleProductUpdated, inventoryService)
    }()
    
    // Consume deletion events
    k.wg.Add(1)
    go func() {
        defer k.wg.Done()
        k.consumeWithRetry("product.deleted", k.readerDeleted,
            k.handleProductDeleted, inventoryService)
    }()
    
    log.Println("All Kafka consumers started")
}

func (k *KafkaConsumer) consumeWithRetry(
    topic string,
    reader *kafka.Reader,
    handler func(context.Context, *kafka.Reader, service.InventoryService) error,
    inventoryService service.InventoryService,
) {
    maxRetries := 10
    retryDelay := 2 * time.Second
    
    for retry := 0; retry < maxRetries; retry++ {
        log.Printf("[%s] Starting consumer (attempt %d/%d)...",
            topic, retry+1, maxRetries)
        
        ctx, cancel := context.WithCancel(context.Background())
        
        err := handler(ctx, reader, inventoryService)
        cancel()
        
        if err == nil || !k.isRunning() {
            return
        }
        
        // Log the error
        if strings.Contains(err.Error(), "context canceled") {
            log.Printf("[%s] Consumer canceled", topic)
            return
        }

        log.Printf("[%s] Consumer error: %v", topic, err)

        // Check whether to keep retrying
        if retry < maxRetries-1 {
            log.Printf("[%s] Waiting %v before retry...", topic, retryDelay)
            time.Sleep(retryDelay)
            retryDelay *= 2 // Exponential backoff
        }
    }

    log.Printf("[%s] Maximum retries exceeded", topic)
}

func (k *KafkaConsumer) isRunning() bool {
    k.mu.Lock()
    defer k.mu.Unlock()
    return k.running
}

func (k *KafkaConsumer) handleProductCreated(ctx context.Context, reader *kafka.Reader, inventoryService service.InventoryService) error {
    log.Println("Consuming ProductCreatedEvents...")
    
    for {
        // Check whether to continue
        if !k.isRunning() {
            return nil
        }
        
        // Use a context with timeout to avoid blocking forever
        readCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
        msg, err := reader.ReadMessage(readCtx)
        cancel()
        
        if err != nil {
            if err == context.DeadlineExceeded {
                // Timeout is expected, continue
                log.Printf("[product.created] Timeout - continuing...")
                continue
            }
            if err == context.Canceled {
                return nil
            }
            return fmt.Errorf("error reading message: %w", err)
        }

        log.Printf("[product.created] Message received - offset: %d, partition: %d",
            msg.Offset, msg.Partition)

        var event models.ProductCreatedEvent
        if err := json.Unmarshal(msg.Value, &event); err != nil {
            log.Printf("Error decoding ProductCreatedEvent: %v", err)
            log.Printf("   Raw message: %s", string(msg.Value))
            // Do not return an error, to avoid stopping the consumer
            continue
        }

        log.Printf("Received ProductCreatedEvent - Product: %s, Initial Stock: %d",
            event.ProductID, event.InitialStock)

        // Try to create/update the inventory
        // First, check whether it already exists
        inventory, _ := inventoryService.GetInventory(event.ProductID)
        if inventory == nil {
            log.Printf("Creating new inventory for product: %s", event.ProductID)
            // Implement a CreateInventory method in the service
            if err := inventoryService.CreateInventory(event.ProductID, event.InitialStock); err != nil {
                log.Printf("Failed to create inventory for product %s: %v",
                    event.ProductID, err)
            } else {
                log.Printf("Inventory created for product: %s", event.ProductID)
            }
        } else {
            log.Printf("Updating existing inventory for product: %s", event.ProductID)
            if err := inventoryService.UpdateInventory(event.ProductID, event.InitialStock); err != nil {
                log.Printf("Failed to update inventory for product %s: %v",
                    event.ProductID, err)
            } else {
                log.Printf("Inventory updated for product: %s", event.ProductID)
            }
        }

        // Manually commit the offset
        if err := reader.CommitMessages(context.Background(), msg); err != nil {
            log.Printf("Error committing offset: %v", err)
        } else {
            log.Printf("Offset committed for product.created - offset: %d", msg.Offset)
        }
    }
}

func (k *KafkaConsumer) handleProductUpdated(ctx context.Context, reader *kafka.Reader, inventoryService service.InventoryService) error {
    log.Println("Consuming ProductUpdatedEvents...")
    
    for {
        if !k.isRunning() {
            return nil
        }
        
        readCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
        msg, err := reader.ReadMessage(readCtx)
        cancel()
        
        if err != nil {
            if err == context.DeadlineExceeded {
                log.Printf("[product.updated] Timeout - continuing...")
                continue
            }
            if err == context.Canceled {
                return nil
            }
            return fmt.Errorf("error reading message: %w", err)
        }
        
        log.Printf("[product.updated] Message received - offset: %d", msg.Offset)
        
        var event models.ProductUpdatedEvent
        if err := json.Unmarshal(msg.Value, &event); err != nil {
            log.Printf("Error decoding ProductUpdatedEvent: %v", err)
            continue
        }

        log.Printf("Received ProductUpdatedEvent - Product: %s, Active: %v",
            event.ProductID, event.Active)

        if !event.Active {
            if err := inventoryService.DeactivateProduct(event.ProductID); err != nil {
                log.Printf("Failed to deactivate inventory for product %s: %v",
                    event.ProductID, err)
            } else {
                log.Printf("Product deactivated in inventory: %s", event.ProductID)
            }
        } else {
            log.Printf("Product re-activated: %s", event.ProductID)
        }

        if err := reader.CommitMessages(context.Background(), msg); err != nil {
            log.Printf("Error committing offset: %v", err)
        }
    }
}

func (k *KafkaConsumer) handleProductDeleted(ctx context.Context, reader *kafka.Reader, inventoryService service.InventoryService) error {
    log.Println("Consuming ProductDeletedEvents...")
    
    for {
        if !k.isRunning() {
            return nil
        }
        
        readCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
        msg, err := reader.ReadMessage(readCtx)
        cancel()
        
        if err != nil {
            if err == context.DeadlineExceeded {
                log.Printf("[product.deleted] Timeout - continuing...")
                continue
            }
            if err == context.Canceled {
                return nil
            }
            return fmt.Errorf("error reading message: %w", err)
        }

        log.Printf("[product.deleted] Message received - offset: %d", msg.Offset)

        var event models.ProductDeletedEvent
        if err := json.Unmarshal(msg.Value, &event); err != nil {
            log.Printf("Error decoding ProductDeletedEvent: %v", err)
            continue
        }

        log.Printf("Received ProductDeletedEvent - Product: %s", event.ProductID)

        if err := inventoryService.DeleteProduct(event.ProductID); err != nil {
            log.Printf("Failed to delete inventory for product %s: %v",
                event.ProductID, err)
        } else {
            log.Printf("Product removed from inventory: %s", event.ProductID)
        }

        if err := reader.CommitMessages(context.Background(), msg); err != nil {
            log.Printf("Error committing offset: %v", err)
        }
    }
}

func (k *KafkaConsumer) Stop() {
    log.Println("Stopping Kafka consumers...")
    
    k.mu.Lock()
    k.running = false
    k.mu.Unlock()
    
    // Wait for consumers to stop
    k.wg.Wait()

    // Close readers
    if k.readerCreated != nil {
        k.readerCreated.Close()
    }
    if k.readerUpdated != nil {
        k.readerUpdated.Close()
    }
    if k.readerDeleted != nil {
        k.readerDeleted.Close()
    }
    
    log.Println("Kafka consumers stopped")
}

func (k *KafkaConsumer) HealthCheck() bool {
    // Try to get Kafka metadata
    conn, err := kafka.Dial("tcp", k.cfg.KafkaBrokers)
    if err != nil {
        log.Printf("Health check failed - Kafka connection error: %v", err)
        return false
    }
    defer conn.Close()
    
    // Check whether topics can be listed
    _, err = conn.ReadPartitions()
    if err != nil {
        log.Printf("Health check failed - Cannot read partitions: %v", err)
        return false
    }
    
    return true
}