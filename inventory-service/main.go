package main

import (
	"encoding/json"
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

    // Initialize repository
    inventoryRepo := repository.NewPostgresRepository(db)

    // Initialize services
    inventoryService := service.NewInventoryService(inventoryRepo)

    // Initialize RabbitMQ consumer
    rabbitMQ, err := messaging.NewRabbitMQConsumer()
    if err != nil {
        log.Printf("Failed to connect to RabbitMQ: %v", err)
        log.Println("Continuing without RabbitMQ...")
    } else {
        defer rabbitMQ.Close()

        // Initialize Kafka producer
        kafkaProducer, err := messaging.NewKafkaProducer()
        if err != nil {
            log.Printf("Failed to connect to Kafka: %v", err)
            log.Println("Continuing without Kafka...")
        } else {
            defer kafkaProducer.Close()

            // Start consuming messages
            go rabbitMQ.ConsumeReserveStockCommands(inventoryService, kafkaProducer)
        }
    }

    // HTTP handlers
    http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("Content-Type", "application/json")
        json.NewEncoder(w).Encode(map[string]string{"status": "OK"})
    })

    http.HandleFunc("/inventory/", func(w http.ResponseWriter, r *http.Request) {
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
    })

    http.HandleFunc("/inventory", func(w http.ResponseWriter, r *http.Request) {
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
    })

    log.Println("Inventory Service started on :8083")
    log.Fatal(http.ListenAndServe(":8083", nil))
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