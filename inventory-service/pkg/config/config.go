package config

import (
	"fmt"
	"net/url"
	"os"
)

type Config struct {
    DatabaseURL string
    RabbitMQURL string
    KafkaBrokers string
}

func Load() *Config {
    return &Config{
        DatabaseURL:  getDatabaseURL(),
        RabbitMQURL:  getRabbitMQURL(),
        KafkaBrokers: getEnv("KAFKA_BROKERS", "localhost:9092"),
    }
}

func getDatabaseURL() string {
    // Docker ->  'postgres'
    // Local -> 'localhost'
    host := getEnv("DB_HOST", "localhost")
    port := getEnv("DB_PORT", "5432")
    user := getEnv("DB_USER", "admin")
    password := getEnv("DB_PASSWORD", "SENHA")
    dbName := getEnv("DB_NAME", "easydora")
    
    encodedPassword := url.QueryEscape(password)
    
    return fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=disable", 
        user, encodedPassword, host, port, dbName)
}

func getRabbitMQURL() string {
    host := getEnv("RABBITMQ_HOST", "localhost")
    port := getEnv("RABBITMQ_PORT", "5672")
    user := getEnv("RABBITMQ_USER", "admin")
    password := getEnv("RABBITMQ_PASSWORD", "SENHA")
    
    encodedPassword := url.QueryEscape(password)

    return fmt.Sprintf("amqp://%s:%s@%s:%s/", 
        user, encodedPassword, host, port)
}

func getEnv(key, defaultValue string) string {
    if value := os.Getenv(key); value != "" {
        return value
    }
    return defaultValue
}