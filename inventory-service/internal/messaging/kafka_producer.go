package messaging

import (
	"context"
	"encoding/json"
	"fmt"
	"inventory-service/internal/models"
	"inventory-service/pkg/config"
	"log"
	"time"

	"github.com/segmentio/kafka-go"
)

type KafkaProducer struct {
	writerReserved    *kafka.Writer
	writerInsufficient *kafka.Writer
}

func NewKafkaProducer() (*KafkaProducer, error) {
	cfg := config.Load()
	
	log.Printf("Initializing KafkaProducer for: %s", cfg.KafkaBrokers)

	// Minimal configuration that works
	createWriter := func(topic string) *kafka.Writer {
		return &kafka.Writer{
			Addr:     kafka.TCP(cfg.KafkaBrokers),
			Topic:    topic,
			Balancer: &kafka.LeastBytes{},
			// Minimal settings
			RequiredAcks: kafka.RequireOne,
			Async:        false,
		}
	}

	return &KafkaProducer{
		writerReserved:    createWriter("stock-reserved"),
		writerInsufficient: createWriter("stock-insufficient"),
	}, nil
}

func (p *KafkaProducer) PublishStockReservedOrder(event *models.StockReservedEvent) error {
	body, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to serialize StockReservedEvent: %w", err)
	}

	message := kafka.Message{
		Value: body,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := p.writerReserved.WriteMessages(ctx, message); err != nil {
		return fmt.Errorf("failed to reserved publish orderId: %w", err)
	}

	return nil
}

func (p *KafkaProducer) PublishStockInsufficientOrder(event *models.StockInsufficientEvent) error {
	body, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to serialize StockInsufficientEvent: %w", err)
	}

	message := kafka.Message{
		Value: body,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := p.writerInsufficient.WriteMessages(ctx, message); err != nil {
		return fmt.Errorf("failed to insufficient publish orderId: %w", err)
	}

	return nil
}

func (k *KafkaProducer) Close() {
	if k.writerReserved != nil {
		k.writerReserved.Close()
	}
	if k.writerInsufficient != nil {
		k.writerInsufficient.Close()
	}
}