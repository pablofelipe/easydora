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
    channels  []*amqp.Channel // Múltiplos canais
    mu        sync.Mutex
    wg        sync.WaitGroup
}

func NewRabbitMQConsumer() (*RabbitMQConsumer, error) {
    cfg := config.Load()
    
    // Tentar conexão com retry
    var conn *amqp.Connection
    var err error
    
    maxRetries := 10
    for i := 0; i < maxRetries; i++ {
        conn, err = amqp.Dial(cfg.RabbitMQURL)
        if err != nil {
            log.Printf("Tentativa %d/%d - RabbitMQ não está pronto: %v", i+1, maxRetries, err)
            time.Sleep(3 * time.Second)
            continue
        }
        log.Println("Conectado ao RabbitMQ")
        break
    }
    
    if err != nil {
        return nil, fmt.Errorf("falha ao conectar ao RabbitMQ após %d tentativas: %w", maxRetries, err)
    }

    return &RabbitMQConsumer{
        conn: conn,
        cfg:  cfg,
    }, nil
}

// createChannel cria um novo canal para uso exclusivo
func (r *RabbitMQConsumer) createChannel() (*amqp.Channel, error) {
    r.mu.Lock()
    defer r.mu.Unlock()
    
    channel, err := r.conn.Channel()
    if err != nil {
        return nil, fmt.Errorf("falha ao criar canal: %w", err)
    }
    
    // Configurar QoS
    err = channel.Qos(
        1,     // prefetch count
        0,     // prefetch size
        false, // global
    )
    if err != nil {
        channel.Close()
        return nil, fmt.Errorf("falha ao configurar QoS: %w", err)
    }
    
    r.channels = append(r.channels, channel)
    return channel, nil
}

// setupExchange cria/verifica o exchange usando um canal dedicado
func (r *RabbitMQConsumer) setupExchange() error {
    channel, err := r.createChannel()
    if err != nil {
        return err
    }
    defer channel.Close()
    
    // Criar exchange
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
        return fmt.Errorf("falha ao criar exchange 'order.exchange': %w", err)
    }
    
    log.Println("Exchange 'order.exchange' (topic) criado/verificado")
    return nil
}

// setupQueue cria uma fila usando um canal dedicado
func (r *RabbitMQConsumer) setupQueue(channel *amqp.Channel, queueName, routingKey string) (amqp.Queue, error) {
    // Declarar a fila
    queue, err := channel.QueueDeclare(
        queueName, // name
        true,      // durable
        false,     // delete when unused
        false,     // exclusive
        false,     // no-wait
        nil,       // arguments
    )
    if err != nil {
        return amqp.Queue{}, fmt.Errorf("falha ao declarar fila '%s': %w", queueName, err)
    }
    log.Printf("Fila '%s' criada/verificada", queueName)

    // Bind da fila ao exchange
    err = channel.QueueBind(
        queue.Name,      // queue name
        routingKey,      // routing key
        "order.exchange", // exchange
        false,           // no-wait
        nil,             // arguments
    )
    if err != nil {
        return amqp.Queue{}, fmt.Errorf("falha ao bindar fila '%s' com routing key '%s': %w", 
            queueName, routingKey, err)
    }
    log.Printf("Fila '%s' bindada com routing key '%s'", queueName, routingKey)

    return queue, nil
}

func (r *RabbitMQConsumer) ConsumeReserveStockCommands(
    inventoryService service.InventoryService, 
    kafkaProducer *KafkaProducer,
) error {
    r.wg.Add(1)
    defer r.wg.Done()
    
    // Criar canal dedicado para este consumer
    channel, err := r.createChannel()
    if err != nil {
        return fmt.Errorf("falha ao criar canal para reserve consumer: %w", err)
    }
    defer channel.Close()
    
    // Configurar fila de reserva
    queue, err := r.setupQueue(channel, "inventory.reserve.queue", "stock.reserve")
    if err != nil {
        return fmt.Errorf("falha ao configurar fila de reserva: %w", err)
    }

    // Iniciar consumer
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
        return fmt.Errorf("falha ao registrar consumer de reserva: %w", err)
    }

    log.Printf("Aguardando ReserveStockCommand na fila: %s (routing key: stock.reserve)", queue.Name)

    // Processar mensagens
    for d := range msgs {
        log.Printf("[RESERVE] Mensagem recebida: %s", string(d.Body))

        var command models.ReserveStockCommand
        if err := json.Unmarshal(d.Body, &command); err != nil {
            log.Printf("[RESERVE] Erro ao decodificar mensagem: %v", err)
            d.Nack(false, false) // Não requeue - mensagem inválida
            continue
        }

        log.Printf("[RESERVE] Processando ReserveStockCommand para order: %s", command.OrderID)

        orderId, success, insufficientEvent, err := inventoryService.ReserveStock(&command)

        if err != nil {
            log.Printf("[RESERVE] Erro ao processar reserva para order %s: %v", command.OrderID, err)
            d.Nack(false, true) // Requeue para tentar novamente
            continue
        }

        // Publicar resultado no Kafka
        if success {
            log.Printf("[RESERVE] Estoque reservado para order: %s", orderId)

            reservedEvent := &models.StockReservedEvent{
                OrderID:   orderId,
                Success:   true,
                Message:   "stock reserved",
                Timestamp: time.Now(),
            }
            if err := kafkaProducer.PublishStockReservedOrder(reservedEvent); err != nil {
                log.Printf("[RESERVE] Falha ao publicar StockReservedEvent: %v", err)
                d.Nack(false, true) // Requeue para tentar novamente
                continue
            }
            log.Printf("[RESERVE] StockReservedEvent publicado para order: %s", orderId)
        } else {
            log.Printf("[RESERVE] Falha na reserva de estoque para order: %s", orderId)

            // Publicar evento de estoque insuficiente
            if insufficientEvent != nil {
                log.Printf("[RESERVE] Publicando StockInsufficientEvent para order: %s, product: %s",
                    insufficientEvent.OrderID, insufficientEvent.ProductID)

                if err := kafkaProducer.PublishStockInsufficientOrder(insufficientEvent); err != nil {
                    log.Printf("[RESERVE] Falha ao publicar StockInsufficientEvent: %v", err)
                } else {
                    log.Printf("[RESERVE] StockInsufficientEvent publicado para order: %s",
                        insufficientEvent.OrderID)
                }
            }
        }

        // Confirmar processamento
        d.Ack(false)
        log.Printf("[RESERVE] Mensagem processada para order: %s", command.OrderID)
    }
    
    return nil
}

func (r *RabbitMQConsumer) ConsumeReleaseStockCommands(inventoryService service.InventoryService) error {
    r.wg.Add(1)
    defer r.wg.Done()
    
    // Criar canal dedicado para este consumer
    channel, err := r.createChannel()
    if err != nil {
        return fmt.Errorf("falha ao criar canal para release consumer: %w", err)
    }
    defer channel.Close()
    
    // Configurar fila de liberação
    queue, err := r.setupQueue(channel, "inventory.release.queue", "stock.release")
    if err != nil {
        return fmt.Errorf("falha ao configurar fila de liberação: %w", err)
    }

    // Iniciar consumer
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
        return fmt.Errorf("falha ao registrar consumer de liberação: %w", err)
    }

    log.Printf("Aguardando ReleaseStockCommand na fila: %s (routing key: stock.release)", queue.Name)

    // Processar mensagens
    for d := range msgs {
        log.Printf("[RELEASE] Mensagem recebida: %s", string(d.Body))

        var command models.ReleaseStockCommand
        if err := json.Unmarshal(d.Body, &command); err != nil {
            log.Printf("[RELEASE] Erro ao decodificar comando de liberação: %v", err)
            d.Nack(false, false) // Não requeue - mensagem inválida
            continue
        }

        log.Printf("[RELEASE] Processando ReleaseStockCommand para order: %s", command.OrderID)

        if err := inventoryService.ReleaseStock(&command); err != nil {
            log.Printf("[RELEASE] Falha ao processar comando de liberação: %v", err)
            d.Nack(false, true) // Requeue para tentar novamente
            continue
        }

        d.Ack(false)
        log.Printf("[RELEASE] Estoque liberado para order: %s", command.OrderID)
    }
    
    return nil
}

// Start inicia todos os consumers em goroutines separadas
func (r *RabbitMQConsumer) Start(
    inventoryService service.InventoryService,
    kafkaProducer *KafkaProducer,
) {
    log.Println("Iniciando RabbitMQ consumers...")

    // Primeiro, criar o exchange
    if err := r.setupExchange(); err != nil {
        log.Fatalf("Falha ao criar exchange: %v", err)
    }

    // Iniciar consumer de reserva em goroutine separada
    go func() {
        if err := r.ConsumeReserveStockCommands(inventoryService, kafkaProducer); err != nil {
            log.Printf("Erro no consumer de reserva: %v", err)
        }
    }()

    // Iniciar consumer de liberação em goroutine separada
    go func() {
        if err := r.ConsumeReleaseStockCommands(inventoryService); err != nil {
            log.Printf("Erro no consumer de liberação: %v", err)
        }
    }()

    log.Println("Todos os consumers RabbitMQ foram iniciados")
    
    // Aguardar goroutines (manter o serviço rodando)
    r.wg.Wait()
}

func (r *RabbitMQConsumer) Close() {
    r.mu.Lock()
    defer r.mu.Unlock()
    
    // Fechar todos os canais
    for _, channel := range r.channels {
        if channel != nil {
            channel.Close()
        }
    }
    
    // Fechar conexão
    if r.conn != nil {
        r.conn.Close()
    }
    
    log.Println("Conexão RabbitMQ fechada")
}