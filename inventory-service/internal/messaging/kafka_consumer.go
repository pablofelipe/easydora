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
    reader *kafka.Reader
}

func NewKafkaConsumer() (*KafkaConsumer, error) {
    cfg := config.Load()
    
    reader := kafka.NewReader(kafka.ReaderConfig{
        Brokers:        []string{cfg.KafkaBrokers},
        Topic:          "product-created",
        GroupID:        "inventory-service-group",
        MinBytes:       10e3, // 10KB
        MaxBytes:       10e6, // 10MB
        MaxWait:        1 * time.Second,
        StartOffset:    kafka.FirstOffset,
        CommitInterval: time.Second,
    })

    return &KafkaConsumer{reader: reader}, nil
}

func (k *KafkaConsumer) ConsumeProductCreatedEvents(inventoryService service.InventoryService) {
    log.Printf("Starting to consume ProductCreatedEvents...")
    
    for {
        msg, err := k.reader.ReadMessage(context.Background())
        if err != nil {
            log.Printf("Error reading message: %v", err)
            continue
        }

        var event models.ProductCreatedEvent
        if err := json.Unmarshal(msg.Value, &event); err != nil {
            log.Printf("Error decoding ProductCreatedEvent: %v", err)
            continue
        }

        log.Printf("Received ProductCreatedEvent - Product: %s, Name: %s, Initial Stock: %d", 
            event.ProductID, event.ProductName, event.InitialStock)

        if err := inventoryService.UpdateInventory(event.ProductID, event.InitialStock); err != nil {
            log.Printf("Failed to initialize inventory for product %s: %v", event.ProductID, err)
        } else {
            log.Printf("Inventory initialized for product: %s", event.ProductID)
        }
    }
}

func (k *KafkaConsumer) Close() {
    if k.reader != nil {
        k.reader.Close()
    }
}