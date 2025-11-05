package database

import (
    "database/sql"
    "fmt"
    "log"
    "inventory-service/pkg/config"
    _ "github.com/lib/pq"
)

func InitPostgres() (*sql.DB, error) {
    cfg := config.Load()
    
    db, err := sql.Open("postgres", cfg.DatabaseURL)
    if err != nil {
        return nil, fmt.Errorf("failed to open database: %v", err)
    }

    if err := db.Ping(); err != nil {
        return nil, fmt.Errorf("failed to ping database: %v", err)
    }

    log.Println("Connected to PostgreSQL successfully")
    return db, nil
}