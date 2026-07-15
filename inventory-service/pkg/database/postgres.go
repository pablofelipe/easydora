package database

import (
    "database/sql"
    "fmt"
    "log"
    "time"

    "inventory-service/pkg/config"

    _ "github.com/lib/pq"
    "github.com/prometheus/client_golang/prometheus"
    "github.com/prometheus/client_golang/prometheus/promauto"
)

// Business metric (ADR-0036/ADR-0038): counts every boot-time
// infrastructure-connection retry attempt, by dependency -- answers "how
// many attempts did it take to become reachable" after the fact via
// Grafana, instead of grepping container logs across restarts.
var infraStartupRetryAttempts = promauto.NewCounterVec(
    prometheus.CounterOpts{
        Name: "infra_startup_retry_attempts_total",
        Help: "Boot-time infrastructure connection retry attempts, by dependency.",
    },
    []string{"dependency"},
)

// InitPostgres retries the initial connectivity check up to maxRetries
// times, fixed delay between attempts -- mirrors the shape already used
// for the RabbitMQ connection in
// internal/messaging/rabbitmq_consumer.go's NewRabbitMQConsumer (bounded
// attempts, fixed delay, log per attempt, fail after exhausting all
// attempts). Closes the one internal asymmetry ADR-0038 found in this
// same service: RabbitMQ already tolerated a slow-starting dependency at
// boot via this exact pattern; Postgres previously had none at all -- a
// single db.Ping() with no retry.
func InitPostgres() (*sql.DB, error) {
    cfg := config.Load()

    db, err := sql.Open("postgres", cfg.DatabaseURL)
    if err != nil {
        return nil, fmt.Errorf("failed to open database: %v", err)
    }

    const maxRetries = 10
    for i := 0; i < maxRetries; i++ {
        err = db.Ping()
        if err == nil {
            log.Println("Connected to PostgreSQL successfully")
            return db, nil
        }
        infraStartupRetryAttempts.WithLabelValues("postgres").Inc()
        log.Printf("Attempt %d/%d - Postgres is not ready: %v", i+1, maxRetries, err)
        time.Sleep(3 * time.Second)
    }

    return nil, fmt.Errorf("failed to ping database after %d attempts: %w", maxRetries, err)
}
