package messaging

import (
	"context"
	"inventory-service/internal/models"
	"inventory-service/pkg/config"
	"log"
	"time"

	"github.com/segmentio/kafka-go"
)

type KafkaProducer struct {
    writer *kafka.Writer
}

func NewKafkaProducer() (*KafkaProducer, error) {
    cfg := config.Load()
    
    writer := &kafka.Writer{
        Addr:         kafka.TCP(cfg.KafkaBrokers),
        Balancer:     &kafka.LeastBytes{},
        BatchTimeout: 10 * time.Millisecond,
        RequiredAcks: kafka.RequireOne,
    }

    return &KafkaProducer{writer: writer}, nil
}

func (k *KafkaProducer) PublishStockReserved(event *models.StockReservedEvent) error {
    topic := "stock-reserved"
    
    message := kafka.Message{
        Topic: topic,
        Value: []byte(event.OrderID),
        Time:  time.Now(),
    }

    ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()

    err := k.writer.WriteMessages(ctx, message)
    if err != nil {
        return err
    }

    log.Printf("Published StockReservedEvent for order: %s", event.OrderID)
    return nil
}

func (k *KafkaProducer) PublishStockInsufficient(event *models.StockInsufficientEvent) error {
    topic := "stock-insufficient"

    message := kafka.Message{
        Topic: topic,
        Value: []byte(event.OrderID),
        Time:  time.Now(),
    }

    ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()

    err := k.writer.WriteMessages(ctx, message)
    if err != nil {
        return err
    }

    log.Printf("Published StockInsufficientEvent for order: %s, product: %s", 
        event.OrderID, event.ProductID)
    return nil
}

func (k *KafkaProducer) Close() {
    if k.writer != nil {
        k.writer.Close()
    }
}