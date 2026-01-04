package messaging

import (
	"context"
	"fmt"
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
	
	log.Printf("🔧 Inicializando KafkaProducer para: %s", cfg.KafkaBrokers)
	
	// Configuração MÍNIMA que funciona
	createWriter := func(topic string) *kafka.Writer {
		return &kafka.Writer{
			Addr:     kafka.TCP(cfg.KafkaBrokers),
			Topic:    topic,
			Balancer: &kafka.LeastBytes{},
			// Configurações mínimas
			RequiredAcks: kafka.RequireOne,
			Async:        false,
		}
	}

	return &KafkaProducer{
		writerReserved:    createWriter("stock-reserved"),
		writerInsufficient: createWriter("stock-insufficient"),
	}, nil
}

func (p *KafkaProducer) PublishStockReservedOrder(orderId string) error {
	message := kafka.Message{
		Value: []byte(orderId),
	}
	
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	
	err := p.writerReserved.WriteMessages(ctx, message)
	if err != nil {
		return fmt.Errorf("failed to reserved publish orderId: %w", err)
	}
	
	return nil
}

func (p *KafkaProducer) PublishStockInsufficientOrder(orderId string) error {
	message := kafka.Message{
		Value: []byte(orderId),
	}
	
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	
	err := p.writerInsufficient.WriteMessages(ctx, message)
	if err != nil {
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