package models

import "time"

type Inventory struct {
    ID        string    `json:"id"`
    ProductID string    `json:"productId"`
    Quantity  int       `json:"quantity"`
    Reserved  int       `json:"reserved"`
    Available bool      `json:"available"`
    Deleted   bool      `json:"deleted"`
    CreatedAt time.Time `json:"created_at"`
    UpdatedAt time.Time `json:"updated_at"`
}

type ReleaseStockCommand struct {
    OrderID   string               `json:"order_id"`
    Items     []ReleaseStockItem   `json:"items"`
    Timestamp time.Time            `json:"timestamp"`
}

type ReleaseStockItem struct {
    ProductID string `json:"productId"`
    Quantity  int    `json:"quantity"`
}

type ReserveStockCommand struct {
    OrderID   string              `json:"orderId"`
    Items     []ReserveStockItem  `json:"items"`
    Timestamp time.Time           `json:"timestamp"`
}

type ReserveStockItem struct {
    ProductID string `json:"productId"`
    Quantity  int    `json:"quantity"`
}

type StockReservedEvent struct {
    OrderID   string    `json:"orderId"`
    Success   bool      `json:"success"`
    Message   string    `json:"message,omitempty"`
    Timestamp time.Time `json:"timestamp"`
}

type StockInsufficientEvent struct {
    OrderID   string    `json:"orderId"`
    ProductID string    `json:"productId"`
    Required  int       `json:"required"`
    Available int       `json:"available"`
    Timestamp time.Time `json:"timestamp"`
}