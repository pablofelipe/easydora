package messaging

import (
	"context"
	"encoding/json"
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
    
    // ✅ Criar writers SEPARADOS para cada tópico
    createWriter := func() *kafka.Writer {
        return &kafka.Writer{
            Addr:         kafka.TCP(cfg.KafkaBrokers),
            Balancer:     &kafka.LeastBytes{},
            BatchTimeout: 10 * time.Millisecond,
            RequiredAcks: kafka.RequireOne,
            Async:        false, // Importante: síncrono para garantir entrega
        }
    }

    return &KafkaProducer{
        writerReserved:    createWriter(),
        writerInsufficient: createWriter(),
    }, nil
}

func (k *KafkaProducer) PublishStockReserved(event *models.StockReservedEvent) error {
    // Pequeno delay para garantir ordem (opcional)
    time.Sleep(100 * time.Millisecond)
    
    // Serializar para JSON
    jsonData, err := json.Marshal(event)
    if err != nil {
        log.Printf("❌ Failed to marshal StockReservedEvent: %v", err)
        return err
    }
    
    log.Printf("📤 Publishing StockReservedEvent: %s", string(jsonData))
    
    // Criar mensagem SEM tópico (o writer já sabe o tópico)
    message := kafka.Message{
        Value: jsonData, // ✅ Apenas o valor
        Time:  time.Now(),
    }

    ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
    defer cancel()

    // ✅ Publicar no tópico reservado
    err = k.writerReserved.WriteMessages(ctx, message)
    if err != nil {
        log.Printf("❌ Failed to publish StockReservedEvent: %v", err)
        return err
    }

    log.Printf("✅ Published StockReservedEvent for order: %s (Success: %v)", 
        event.OrderID, event.Success)
    return nil
}

func (k *KafkaProducer) PublishStockInsufficient(event *models.StockInsufficientEvent) error {
    // Serializar para JSON
    jsonData, err := json.Marshal(event)
    if err != nil {
        log.Printf("❌ Failed to marshal StockInsufficientEvent: %v", err)
        return err
    }

    log.Printf("📤 Publishing StockInsufficientEvent: %s", string(jsonData))
    
    message := kafka.Message{
        Value: jsonData, // ✅ Apenas o valor
        Time:  time.Now(),
    }

    ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
    defer cancel()

    // ✅ Publicar no tópico de insuficiência
    err = k.writerInsufficient.WriteMessages(ctx, message)
    if err != nil {
        log.Printf("❌ Failed to publish StockInsufficientEvent: %v", err)
        return err
    }

    log.Printf("✅ Published StockInsufficientEvent for order: %s, product: %s", 
        event.OrderID, event.ProductID)
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