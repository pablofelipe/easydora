package messaging

import (
	"context"
	"encoding/json"
	"inventory-service/internal/models"
	"inventory-service/internal/service"
	"inventory-service/pkg/config"
	"log"
	"time"

	"github.com/segmentio/kafka-go"
)

type KafkaConsumer struct {
    readerCreated *kafka.Reader
    readerUpdated *kafka.Reader
    readerDeleted *kafka.Reader
}

func NewKafkaConsumer() (*KafkaConsumer, error) {
    cfg := config.Load()
    
    createReader := func(topic string) *kafka.Reader {
        return kafka.NewReader(kafka.ReaderConfig{
            Brokers:        []string{cfg.KafkaBrokers},
            Topic:          topic,
            GroupID:        "inventory-service-group",
            MinBytes:       10e3,
            MaxBytes:       10e6,
            MaxWait:        30 * time.Second,
            StartOffset:    kafka.FirstOffset,
            CommitInterval: time.Second,
            Logger:         kafka.LoggerFunc(log.Printf),
            ErrorLogger:    kafka.LoggerFunc(log.Printf),
        })
    }

    return &KafkaConsumer{
        readerCreated: createReader("product.created"),
        readerUpdated: createReader("product.updated"),
        readerDeleted: createReader("product.deleted"),
    }, nil
}

func (k *KafkaConsumer) StartConsuming(inventoryService service.InventoryService) {
    log.Printf("Starting Kafka consumers...")
    
    // Consumir eventos de criação
    go k.consumeProductCreatedEvents(inventoryService)
    
    // Consumir eventos de atualização
    go k.consumeProductUpdatedEvents(inventoryService)
    
    // Consumir eventos de deleção
    go k.consumeProductDeletedEvents(inventoryService)
}

func (k *KafkaConsumer) consumeProductCreatedEvents(inventoryService service.InventoryService) {
    log.Printf("Consuming ProductCreatedEvents...")
    
    for {
        msg, err := k.readerCreated.ReadMessage(context.Background())
        if err != nil {
            log.Printf("Error reading ProductCreatedEvent: %v", err)
            continue
        }

        var event models.ProductCreatedEvent
        if err := json.Unmarshal(msg.Value, &event); err != nil {
            log.Printf("Error decoding ProductCreatedEvent: %v", err)
            continue
        }

        log.Printf("📦 Received ProductCreatedEvent - Product: %s, Initial Stock: %d", 
            event.ProductID, event.InitialStock)

        if err := inventoryService.UpdateInventory(event.ProductID, event.InitialStock); err != nil {
            log.Printf("❌ Failed to initialize inventory for product %s: %v", event.ProductID, err)
        } else {
            log.Printf("✅ Inventory initialized for product: %s", event.ProductID)
        }
    }
}

func (k *KafkaConsumer) consumeProductUpdatedEvents(inventoryService service.InventoryService) {
    log.Printf("Consuming ProductUpdatedEvents...")
    
    for {
        msg, err := k.readerUpdated.ReadMessage(context.Background())
        if err != nil {
            log.Printf("Error reading ProductUpdatedEvent: %v", err)
            continue
        }

        var event models.ProductUpdatedEvent
        if err := json.Unmarshal(msg.Value, &event); err != nil {
            log.Printf("Error decoding ProductUpdatedEvent: %v", err)
            continue
        }

        log.Printf("✏️ Received ProductUpdatedEvent - Product: %s, Active: %v", 
            event.ProductID, event.Active)

        // Se o produto foi desativado, marcar o estoque como indisponível
        if !event.Active {
            if err := inventoryService.DeactivateProduct(event.ProductID); err != nil {
                log.Printf("❌ Failed to deactivate inventory for product %s: %v", event.ProductID, err)
            } else {
                log.Printf("✅ Product deactivated in inventory: %s", event.ProductID)
            }
        } else {
            log.Printf("ℹ️ Product updated (still active): %s", event.ProductID)
        }
    }
}

func (k *KafkaConsumer) consumeProductDeletedEvents(inventoryService service.InventoryService) {
    log.Printf("Consuming ProductDeletedEvents...")
    
    for {
        msg, err := k.readerDeleted.ReadMessage(context.Background())
        if err != nil {
            log.Printf("Error reading ProductDeletedEvent: %v", err)
            continue
        }

        var event models.ProductDeletedEvent
        if err := json.Unmarshal(msg.Value, &event); err != nil {
            log.Printf("Error decoding ProductDeletedEvent: %v", err)
            continue
        }

        log.Printf("🗑️ Received ProductDeletedEvent - Product: %s", event.ProductID)

        // Marcar estoque como deletado/indisponível
        if err := inventoryService.DeleteProduct(event.ProductID); err != nil {
            log.Printf("❌ Failed to delete inventory for product %s: %v", event.ProductID, err)
        } else {
            log.Printf("✅ Product removed from inventory: %s", event.ProductID)
        }
    }
}

func (k *KafkaConsumer) Close() {
    if k.readerCreated != nil {
        k.readerCreated.Close()
    }
    if k.readerUpdated != nil {
        k.readerUpdated.Close()
    }
    if k.readerDeleted != nil {
        k.readerDeleted.Close()
    }
}
