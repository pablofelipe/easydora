package messaging

import (
	"encoding/json"
	"inventory-service/internal/models"
	"inventory-service/internal/service"
	"inventory-service/pkg/config"
	"log"

	"github.com/streadway/amqp"
)

type RabbitMQConsumer struct {
    conn *amqp.Connection
    channel *amqp.Channel
}

func NewRabbitMQConsumer() (*RabbitMQConsumer, error) {
    cfg := config.Load()
    
    conn, err := amqp.Dial(cfg.RabbitMQURL)
    if err != nil {
        return nil, err
    }

    channel, err := conn.Channel()
    if err != nil {
        conn.Close()
        return nil, err
    }

    queue, err := channel.QueueDeclare(
        "inventory.reserve.queue", // name
        true,            // durable
        false,           // delete when unused
        false,           // exclusive
        false,           // no-wait
        nil,             // arguments
    )
    if err != nil {
        conn.Close()
        return nil, err
    }

    err = channel.QueueBind(
        queue.Name, // queue name
        "stock.reserve",  // routing key
        "order.exchange",    // exchange
        false,
        nil,
    )
    if err != nil {
        conn.Close()
        return nil, err
    }

    return &RabbitMQConsumer{
        conn: conn,
        channel: channel,
    }, nil
}

func (r *RabbitMQConsumer) ConsumeReleaseStockCommands(inventoryService service.InventoryService) {
    // Declare release queue
    queue, err := r.channel.QueueDeclare(
        "inventory.release.queue",
        true, false, false, false, nil,
    )
    if err != nil {
        log.Printf("⚠️ Failed to declare release queue: %v", err)
        return
    }
    
    err = r.channel.QueueBind(
        queue.Name,
        "stock.release",  // Nova routing key
        "order.exchange",
        false, nil,
    )
    if err != nil {
        log.Printf("⚠️ Failed to bind release queue: %v", err)
        return
    }
    
    msgs, err := r.channel.Consume(
        queue.Name,
        "", false, false, false, false, nil,
    )
    if err != nil {
        log.Printf("⚠️ Failed to consume release queue: %v", err)
        return
    }
    
    go func() {
        for d := range msgs {
            var command models.ReleaseStockCommand
            if err := json.Unmarshal(d.Body, &command); err != nil {
                log.Printf("❌ Error decoding release command: %v", err)
                d.Nack(false, false) // Não requeue - mensagem inválida
                continue
            }
            
            log.Printf("📥 Received ReleaseStockCommand for order: %s", command.OrderID)
            
            if err := inventoryService.ReleaseStock(&command); err != nil {
                log.Printf("❌ Failed to process release command: %v", err)
                d.Nack(false, true) // Requeue para tentar novamente
                continue
            }
            
            d.Ack(false)
            log.Printf("✅ Stock released for order: %s", command.OrderID)
        }
    }()
    
    log.Printf("👂 Waiting for ReleaseStockCommand messages on queue: %s", queue.Name)
}

func (r *RabbitMQConsumer) ConsumeReserveStockCommands(
    inventoryService service.InventoryService, 
    kafkaProducer *KafkaProducer,
) {
    msgs, err := r.channel.Consume(
        "inventory.reserve.queue", // queue
        "",              // consumer
        false,           // auto-ack (we'll do manual ack)
        false,           // exclusive
        false,           // no-local
        false,           // no-wait
        nil,             // args
    )
    if err != nil {
        log.Fatal("Failed to register consumer:", err)
    }

    forever := make(chan bool)

    go func() {
        for d := range msgs {
            var command models.ReserveStockCommand
            if err := json.Unmarshal(d.Body, &command); err != nil {
                log.Printf("Error decoding message: %v", err)
                d.Nack(false, false) // reject and don't requeue
                continue
            }

            log.Printf("Received ReserveStockCommand for order: %s", command.OrderID)

            // Process the command
            reservedEvent, insufficientEvents := inventoryService.ReserveStock(&command)

            // Publish result to Kafka
            if reservedEvent.Success {
                if err := kafkaProducer.PublishStockReserved(reservedEvent); err != nil {
                    log.Printf("Failed to publish StockReservedEvent: %v", err)
                    d.Nack(false, true) // requeue the message
                    continue
                }
                log.Printf("Stock reserved successfully for order: %s", command.OrderID)
            } else {
                // Publish insufficient events for each failed product
                for _, event := range insufficientEvents {
                    if err := kafkaProducer.PublishStockInsufficient(event); err != nil {
                        log.Printf("Failed to publish StockInsufficientEvent: %v", err)
                    }
                }
                log.Printf("Stock reservation failed for order: %s", command.OrderID)
            }

            // Acknowledge the message
            d.Ack(false)
        }
    }()

    log.Printf("Waiting for ReserveStockCommand messages...")
    <-forever
}

func (r *RabbitMQConsumer) Close() {
    r.channel.Close()
    r.conn.Close()
}