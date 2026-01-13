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
    
    // Validar configuração
    if cfg.KafkaBrokers == "" {
        return nil, fmt.Errorf("KafkaBrokers não configurado")
    }
    
    log.Printf("🔧 Configurando Kafka Consumer para: %s", cfg.KafkaBrokers)
    
    k := &KafkaConsumer{
        cfg: cfg,
    }
    
    // Inicializar readers
    if err := k.initializeReaders(); err != nil {
        return nil, fmt.Errorf("falha ao inicializar readers: %w", err)
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
        log.Printf("📡 Tentativa %d/%d de conectar ao Kafka...", i+1, maxRetries)
        
        if err := k.testKafkaConnection(); err != nil {
            lastErr = err
            log.Printf("❌ Falha na conexão com Kafka: %v", err)
            time.Sleep(2 * time.Second)
            continue
        }
        
        k.readerCreated = k.createReader("product.created")
        k.readerUpdated = k.createReader("product.updated")
        k.readerDeleted = k.createReader("product.deleted")
        
        log.Println("✅ Todos os Kafka readers criados com sucesso")
        return nil
    }
    
    return fmt.Errorf("falha ao conectar ao Kafka após %d tentativas: %v", maxRetries, lastErr)
}

func (k *KafkaConsumer) testKafkaConnection() error {
    conn, err := kafka.Dial("tcp", k.cfg.KafkaBrokers)
    if err != nil {
        return fmt.Errorf("falha ao conectar ao Kafka: %w", err)
    }
    defer conn.Close()
    
    _, err = conn.ReadPartitions()
    if err != nil {
        return fmt.Errorf("falha ao ler partições: %w", err)
    }
    
    return nil
}

func (k *KafkaConsumer) StartConsuming(inventoryService service.InventoryService) {
    log.Println("🚀 Iniciando Kafka consumers...")
    
    k.mu.Lock()
    k.running = true
    k.mu.Unlock()
    
    // Consumir eventos de criação
    k.wg.Add(1)
    go func() {
        defer k.wg.Done()
        k.consumeWithRetry("product.created", k.readerCreated, 
            k.handleProductCreated, inventoryService)
    }()
    
    // Consumir eventos de atualização
    k.wg.Add(1)
    go func() {
        defer k.wg.Done()
        k.consumeWithRetry("product.updated", k.readerUpdated,
            k.handleProductUpdated, inventoryService)
    }()
    
    // Consumir eventos de deleção
    k.wg.Add(1)
    go func() {
        defer k.wg.Done()
        k.consumeWithRetry("product.deleted", k.readerDeleted,
            k.handleProductDeleted, inventoryService)
    }()
    
    log.Println("✅ Todos os Kafka consumers iniciados")
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
        log.Printf("👂 [%s] Iniciando consumer (tentativa %d/%d)...", 
            topic, retry+1, maxRetries)
        
        ctx, cancel := context.WithCancel(context.Background())
        
        err := handler(ctx, reader, inventoryService)
        cancel()
        
        if err == nil || !k.isRunning() {
            return
        }
        
        // Log do erro
        if strings.Contains(err.Error(), "context canceled") {
            log.Printf("⚠️ [%s] Consumer cancelado", topic)
            return
        }
        
        log.Printf("❌ [%s] Erro no consumer: %v", topic, err)
        
        // Verificar se deve continuar tentando
        if retry < maxRetries-1 {
            log.Printf("⏳ [%s] Aguardando %v antes de retry...", topic, retryDelay)
            time.Sleep(retryDelay)
            retryDelay *= 2 // Exponential backoff
        }
    }
    
    log.Printf("💥 [%s] Máximo de retries excedido", topic)
}

func (k *KafkaConsumer) isRunning() bool {
    k.mu.Lock()
    defer k.mu.Unlock()
    return k.running
}

func (k *KafkaConsumer) handleProductCreated(ctx context.Context, reader *kafka.Reader, inventoryService service.InventoryService) error {
    log.Println("👂 Consumindo ProductCreatedEvents...")
    
    for {
        // Verificar se deve continuar
        if !k.isRunning() {
            return nil
        }
        
        // Usar contexto com timeout para evitar bloqueios eternos
        readCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
        msg, err := reader.ReadMessage(readCtx)
        cancel()
        
        if err != nil {
            if err == context.DeadlineExceeded {
                // Timeout é esperado, continuar
                log.Printf("⏳ [product.created] Timeout - continuando...")
                continue
            }
            if err == context.Canceled {
                return nil
            }
            return fmt.Errorf("erro ao ler mensagem: %w", err)
        }
        
        log.Printf("📥 [product.created] Mensagem recebida - offset: %d, partition: %d", 
            msg.Offset, msg.Partition)
        
        var event models.ProductCreatedEvent
        if err := json.Unmarshal(msg.Value, &event); err != nil {
            log.Printf("❌ Erro ao decodificar ProductCreatedEvent: %v", err)
            log.Printf("   Mensagem raw: %s", string(msg.Value))
            // Não retornar erro para não parar o consumer
            continue
        }
        
        log.Printf("📦 Received ProductCreatedEvent - Product: %s, Initial Stock: %d", 
            event.ProductID, event.InitialStock)
        
        // Tentar criar/atualizar o inventário
        // Primeiro, verificar se já existe
        inventory, _ := inventoryService.GetInventory(event.ProductID)
        if inventory == nil {
            log.Printf("✏️ Criando novo inventory para product: %s", event.ProductID)
            // Implemente um método CreateInventory no service
            if err := inventoryService.CreateInventory(event.ProductID, event.InitialStock); err != nil {
                log.Printf("❌ Failed to create inventory for product %s: %v", 
                    event.ProductID, err)
            } else {
                log.Printf("✅ Inventory criado para product: %s", event.ProductID)
            }
        } else {
            log.Printf("✏️ Atualizando inventory existente para product: %s", event.ProductID)
            if err := inventoryService.UpdateInventory(event.ProductID, event.InitialStock); err != nil {
                log.Printf("❌ Failed to update inventory for product %s: %v", 
                    event.ProductID, err)
            } else {
                log.Printf("✅ Inventory atualizado para product: %s", event.ProductID)
            }
        }
        
        // Commit manual do offset
        if err := reader.CommitMessages(context.Background(), msg); err != nil {
            log.Printf("⚠️ Erro ao commitar offset: %v", err)
        } else {
            log.Printf("✅ Offset commitado para product.created - offset: %d", msg.Offset)
        }
    }
}

func (k *KafkaConsumer) handleProductUpdated(ctx context.Context, reader *kafka.Reader, inventoryService service.InventoryService) error {
    log.Println("👂 Consumindo ProductUpdatedEvents...")
    
    for {
        if !k.isRunning() {
            return nil
        }
        
        readCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
        msg, err := reader.ReadMessage(readCtx)
        cancel()
        
        if err != nil {
            if err == context.DeadlineExceeded {
                log.Printf("⏳ [product.updated] Timeout - continuando...")
                continue
            }
            if err == context.Canceled {
                return nil
            }
            return fmt.Errorf("erro ao ler mensagem: %w", err)
        }
        
        log.Printf("📥 [product.updated] Mensagem recebida - offset: %d", msg.Offset)
        
        var event models.ProductUpdatedEvent
        if err := json.Unmarshal(msg.Value, &event); err != nil {
            log.Printf("❌ Erro ao decodificar ProductUpdatedEvent: %v", err)
            continue
        }
        
        log.Printf("✏️ Received ProductUpdatedEvent - Product: %s, Active: %v", 
            event.ProductID, event.Active)
        
        if !event.Active {
            if err := inventoryService.DeactivateProduct(event.ProductID); err != nil {
                log.Printf("❌ Failed to deactivate inventory for product %s: %v", 
                    event.ProductID, err)
            } else {
                log.Printf("✅ Product deactivated in inventory: %s", event.ProductID)
            }
        } else {
            log.Printf("ℹ️ Product re-activated: %s", event.ProductID)
        }
        
        if err := reader.CommitMessages(context.Background(), msg); err != nil {
            log.Printf("⚠️ Erro ao commitar offset: %v", err)
        }
    }
}

func (k *KafkaConsumer) handleProductDeleted(ctx context.Context, reader *kafka.Reader, inventoryService service.InventoryService) error {
    log.Println("👂 Consumindo ProductDeletedEvents...")
    
    for {
        if !k.isRunning() {
            return nil
        }
        
        readCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
        msg, err := reader.ReadMessage(readCtx)
        cancel()
        
        if err != nil {
            if err == context.DeadlineExceeded {
                log.Printf("⏳ [product.deleted] Timeout - continuando...")
                continue
            }
            if err == context.Canceled {
                return nil
            }
            return fmt.Errorf("erro ao ler mensagem: %w", err)
        }
        
        log.Printf("📥 [product.deleted] Mensagem recebida - offset: %d", msg.Offset)
        
        var event models.ProductDeletedEvent
        if err := json.Unmarshal(msg.Value, &event); err != nil {
            log.Printf("❌ Erro ao decodificar ProductDeletedEvent: %v", err)
            continue
        }
        
        log.Printf("🗑️ Received ProductDeletedEvent - Product: %s", event.ProductID)
        
        if err := inventoryService.DeleteProduct(event.ProductID); err != nil {
            log.Printf("❌ Failed to delete inventory for product %s: %v", 
                event.ProductID, err)
        } else {
            log.Printf("✅ Product removed from inventory: %s", event.ProductID)
        }
        
        if err := reader.CommitMessages(context.Background(), msg); err != nil {
            log.Printf("⚠️ Erro ao commitar offset: %v", err)
        }
    }
}

func (k *KafkaConsumer) Stop() {
    log.Println("🛑 Parando Kafka consumers...")
    
    k.mu.Lock()
    k.running = false
    k.mu.Unlock()
    
    // Aguardar consumers pararem
    k.wg.Wait()
    
    // Fechar readers
    if k.readerCreated != nil {
        k.readerCreated.Close()
    }
    if k.readerUpdated != nil {
        k.readerUpdated.Close()
    }
    if k.readerDeleted != nil {
        k.readerDeleted.Close()
    }
    
    log.Println("✅ Kafka consumers parados")
}

func (k *KafkaConsumer) HealthCheck() bool {
    // Tentar obter metadata do Kafka
    conn, err := kafka.Dial("tcp", k.cfg.KafkaBrokers)
    if err != nil {
        log.Printf("❌ Health check failed - Kafka connection error: %v", err)
        return false
    }
    defer conn.Close()
    
    // Verificar se pode listar tópicos
    _, err = conn.ReadPartitions()
    if err != nil {
        log.Printf("❌ Health check failed - Cannot read partitions: %v", err)
        return false
    }
    
    return true
}