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
	"strconv"
	"time"

	"github.com/joho/godotenv"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
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
    //
    // healthHandler is registered under both /health (bare, for Docker's
    // own HEALTHCHECK hitting the container directly) and /inventory/health
    // (self-namespaced, so a call proxied through the Gateway -- which
    // forwards the incoming path unchanged, see ADR-0025 -- reaches the
    // same handler instead of being swallowed by the /inventory/ catch-all
    // below, which would otherwise treat "health" as a product ID).
    http.Handle("/health", withCORS(correlation.Middleware(withMetrics("/health", http.HandlerFunc(healthHandler)))))
    http.Handle("/inventory/health", withCORS(correlation.Middleware(withMetrics("/inventory/health", http.HandlerFunc(healthHandler)))))

    // Prometheus scrape endpoint (see ADR-0036). promhttp.Handler() serves
    // the default registry, which already includes Go runtime metrics
    // (goroutines, heap) and process metrics with zero custom collectors.
    http.Handle("/metrics", promhttp.Handler())

    http.Handle("/inventory/", withCORS(correlation.Middleware(withMetrics("/inventory/{productId}", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
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
    })))))

    http.Handle("/inventory", withCORS(correlation.Middleware(withMetrics("/inventory", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
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
    })))))

    log.Println("Inventory Service started on :8083")
    log.Fatal(http.ListenAndServe(":8083", nil))
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(map[string]string{"status": "OK"})
}

// HTTP request rate/latency/errors: promhttp.Handler() alone only exposes Go
// runtime metrics, not request-level ones (unlike Micrometer's automatic
// http_server_requests_seconds in the four Spring services) -- this is the
// small amount of custom instrumentation that gap actually requires. route
// is passed explicitly per registration (net/http has no route-template
// introspection like Gin's FullPath()), so it stays a fixed, bounded label
// regardless of the raw path a client sends. See ADR-0036.
var (
    httpRequestsTotal = promauto.NewCounterVec(
        prometheus.CounterOpts{
            Name: "http_requests_total",
            Help: "Total HTTP requests handled by this service, by route, method and status.",
        },
        []string{"method", "route", "status"},
    )
    httpRequestDuration = promauto.NewHistogramVec(
        prometheus.HistogramOpts{
            Name:    "http_request_duration_seconds",
            Help:    "HTTP request duration in seconds, by route and method.",
            Buckets: prometheus.DefBuckets,
        },
        []string{"method", "route"},
    )
)

type statusRecordingWriter struct {
    http.ResponseWriter
    status int
}

func (w *statusRecordingWriter) WriteHeader(code int) {
    w.status = code
    w.ResponseWriter.WriteHeader(code)
}

func withMetrics(route string, next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        start := time.Now()
        rec := &statusRecordingWriter{ResponseWriter: w, status: http.StatusOK}

        next.ServeHTTP(rec, r)

        httpRequestsTotal.WithLabelValues(r.Method, route, strconv.Itoa(rec.status)).Inc()
        httpRequestDuration.WithLabelValues(r.Method, route).Observe(time.Since(start).Seconds())
    })
}

// withCORS lets the frontend call this service through the Gateway from a
// browser. inventory-service has no auth/security layer of
// its own to fight (unlike the Spring services), so a plain header-setting
// wrapper that also short-circuits the OPTIONS preflight is enough.
func withCORS(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("Access-Control-Allow-Origin", "*")
        w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        w.Header().Set("Access-Control-Allow-Headers", "*")
        // Response headers are NOT readable by browser JS unless explicitly
        // exposed -- without this, fetch()'s response.headers.get(...)
        // always returns null for these in a real browser even though curl
        // (not subject to CORS) sees them fine.
        w.Header().Set("Access-Control-Expose-Headers", "X-Correlation-Id, X-Request-Id")

        if r.Method == http.MethodOptions {
            w.WriteHeader(http.StatusNoContent)
            return
        }

        next.ServeHTTP(w, r)
    })
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