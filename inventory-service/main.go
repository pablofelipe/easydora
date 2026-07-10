package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"easydora/correlation-commons"
	"inventory-service/internal/messaging"
	"inventory-service/internal/repository"
	"inventory-service/internal/service"
	"inventory-service/pkg/database"
	"log"
	"net/http"
	"os"
	"path/filepath"

	"github.com/joho/godotenv"
)

func main() {
    // Load .env file
    loadEnv();

    // Initialize database
    db, err := database.InitPostgres()
    if err != nil {
        log.Fatal("Failed to connect to database:", err)
    }
    defer db.Close()

    if err := runInitScript(db); err != nil {
        log.Fatal("Failed to run initialization script:", err)
    }

    // Initialize repository
    inventoryRepo := repository.NewPostgresRepository(db)

    // Initialize services
    inventoryService := service.NewInventoryService(inventoryRepo)

    // Initialize RabbitMQ consumer (sole messaging transport, ADR-0007)
    rabbitMQ, err := messaging.NewRabbitMQConsumer()
    if err != nil {
        log.Fatal("Failed to connect to RabbitMQ:", err)
    }
    defer rabbitMQ.Close()

    if err := rabbitMQ.SetupOrderExchange(); err != nil {
        log.Fatal("Failed to set up order.exchange:", err)
    }
    if err := rabbitMQ.SetupProductExchange(); err != nil {
        log.Fatal("Failed to set up product.exchange:", err)
    }

    // Outbox poller (ADR-0007): publishes stock.reserved/stock.insufficient
    // events written atomically by ReserveStockForOrder.
    outboxPublisher, err := rabbitMQ.StartOutboxPublisher(inventoryRepo)
    if err != nil {
        log.Fatal("Failed to start outbox publisher:", err)
    }
    defer outboxPublisher.Stop()

    // Start consuming messages
    go rabbitMQ.ConsumeReserveStockCommands(inventoryService)
    go rabbitMQ.ConsumeReleaseStockCommands(inventoryService)

    go rabbitMQ.ConsumeProductCreatedEvents(inventoryService)
    go rabbitMQ.ConsumeProductUpdatedEvents(inventoryService)
    go rabbitMQ.ConsumeProductDeletedEvents(inventoryService)

    // HTTP handlers -- each wrapped in correlation.Middleware so every
    // request gets a CorrelationId (reused from X-Correlation-Id if the
    // caller sent one) and a fresh RequestId, both echoed back as response
    // headers and available to every log line for the request.
    http.Handle("/health", correlation.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("Content-Type", "application/json")
        json.NewEncoder(w).Encode(map[string]string{"status": "OK"})
    })))

    http.Handle("/inventory/", correlation.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        productID := r.URL.Path[len("/inventory/"):]
        
        switch r.Method {
        case "GET":
            inventory, err := inventoryService.GetInventory(productID)
            if err != nil {
                http.Error(w, err.Error(), http.StatusInternalServerError)
                return
            }
            
            if inventory == nil {
                http.Error(w, "Inventory not found", http.StatusNotFound)
                return
            }
            
            w.Header().Set("Content-Type", "application/json")
            json.NewEncoder(w).Encode(inventory)
            
        default:
            http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
        }
    })))

    http.Handle("/inventory", correlation.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        if r.Method != "POST" {
            http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
            return
        }

        var request struct {
            ProductID string `json:"product_id"`
            Quantity  int    `json:"quantity"`
        }

        if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
            http.Error(w, "Invalid request body", http.StatusBadRequest)
            return
        }

        err := inventoryService.UpdateInventory(request.ProductID, request.Quantity)
        if err != nil {
            http.Error(w, err.Error(), http.StatusInternalServerError)
            return
        }

        w.Header().Set("Content-Type", "application/json")
        json.NewEncoder(w).Encode(map[string]string{"message": "Inventory updated successfully"})
    })))

    log.Println("Inventory Service started on :8083")
    log.Fatal(http.ListenAndServe(":8083", nil))
}

func runInitScript(db *sql.DB) error {
    // script init.sql
    scriptPath := filepath.Join("scripts", "init.sql")
    
    // Read content of the file
    content, err := os.ReadFile(scriptPath)
    if err != nil {
        return fmt.Errorf("failed to read init.sql: %v", err)
    }
    
    // Run the SQL script
    _, err = db.Exec(string(content))
    if err != nil {
        return fmt.Errorf("failed to execute init.sql: %v", err)
    }
    
    log.Println("Database initialization script executed successfully")
    return nil
}

func loadEnv() {
    // Get the current working directory
    cwd, err := os.Getwd()
    if err != nil {
        log.Printf("Error getting current directory: %v", err)
        return
    }

    // Try to load .env from current directory
    envPath := filepath.Join(cwd, ".env")
    err = godotenv.Load(envPath)
    if err != nil {
        log.Printf("No .env file found at %s, using environment variables", envPath)
        
        // Try parent directory (in case we're in a subdirectory)
        parentEnvPath := filepath.Join(cwd, "..", ".env")
        err = godotenv.Load(parentEnvPath)
        if err != nil {
            log.Printf("No .env file found at %s either", parentEnvPath)
        } else {
            log.Printf("Loaded .env from parent directory: %s", parentEnvPath)
        }
    } else {
        log.Printf("Loaded .env from: %s", envPath)
    }
    
    // Debug: show what environment variables are loaded
    log.Printf("DB_HOST: %s", os.Getenv("DB_HOST"))
    log.Printf("DB_USER: %s", os.Getenv("DB_USER"))
}