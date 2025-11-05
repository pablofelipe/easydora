package repository

import (
	"database/sql"
	"fmt"
	"inventory-service/internal/models"
	"log"
)

type InventoryRepository interface {
    GetByProductID(productID string) (*models.Inventory, error)
    UpdateQuantity(productID string, newQuantity int) error
    ReserveStock(productID string, quantity int) error
    ReleaseStock(productID string, quantity int) error
}

type PostgresRepository struct {
    db *sql.DB
}

func NewPostgresRepository(db *sql.DB) *PostgresRepository {
    return &PostgresRepository{db: db}
}

func (r *PostgresRepository) GetByProductID(productID string) (*models.Inventory, error) {
    query := `SELECT id, product_id, quantity, reserved, created_at, updated_at 
              FROM inventory_schema.inventory WHERE product_id = $1`
    
    var inv models.Inventory
    err := r.db.QueryRow(query, productID).Scan(
        &inv.ID, &inv.ProductID, &inv.Quantity, &inv.Reserved,
        &inv.CreatedAt, &inv.UpdatedAt,
    )
    
    if err == sql.ErrNoRows {
        return nil, nil
    }
    if err != nil {
        return nil, fmt.Errorf("failed to get inventory: %v", err)
    }
    
    return &inv, nil
}

func (r *PostgresRepository) UpdateQuantity(productID string, newQuantity int) error {
    log.Printf("✏️ Tentando UPSERT inventory - product_id: %s, quantity: %d", productID, newQuantity)
    
    query := `
        INSERT INTO inventory_schema.inventory (product_id, quantity) 
        VALUES ($1, $2)
        ON CONFLICT (product_id) 
        DO UPDATE SET 
            quantity = $2,
            updated_at = NOW()
        WHERE inventory_schema.inventory.product_id = $1
    `
    
    result, err := r.db.Exec(query, productID, newQuantity)
    if err != nil {
        log.Printf("❌ Erro no UPSERT: %v", err)
        return fmt.Errorf("failed to upsert inventory: %v", err)
    }
    
    rows, err := result.RowsAffected()
    if err != nil {
        return fmt.Errorf("failed to get rows affected: %v", err)
    }
    
    log.Printf("✅ UPSERT realizado - Rows affected: %d", rows)
    return nil
}

func (r *PostgresRepository) ReserveStock(productID string, quantity int) error {
    // Use transaction to ensure atomicity
    tx, err := r.db.Begin()
    if err != nil {
        return fmt.Errorf("failed to begin transaction: %v", err)
    }
    defer tx.Rollback()

    // Check available stock
    var currentQuantity, reserved int
    err = tx.QueryRow(
        "SELECT quantity, reserved FROM inventory_schema.inventory WHERE product_id = $1 FOR UPDATE",
        productID,
    ).Scan(&currentQuantity, &reserved)
    
    if err != nil {
        return fmt.Errorf("failed to get inventory for update: %v", err)
    }

    available := currentQuantity - reserved
    if available < quantity {
        return fmt.Errorf("insufficient stock: available %d, required %d", available, quantity)
    }

    // Reserve stock
    _, err = tx.Exec(
        "UPDATE inventory_schema.inventory SET reserved = reserved + $1, updated_at = NOW() WHERE product_id = $2",
        quantity, productID,
    )
    if err != nil {
        return fmt.Errorf("failed to reserve stock: %v", err)
    }

    return tx.Commit()
}

func (r *PostgresRepository) ReleaseStock(productID string, quantity int) error {
    query := `UPDATE inventory_schema.inventory SET reserved = reserved - $1, updated_at = NOW() 
              WHERE product_id = $2 AND reserved >= $1`
    
    result, err := r.db.Exec(query, quantity, productID)
    if err != nil {
        return fmt.Errorf("failed to release stock: %v", err)
    }
    
    rows, err := result.RowsAffected()
    if err != nil {
        return fmt.Errorf("failed to get rows affected: %v", err)
    }
    
    if rows == 0 {
        return fmt.Errorf("no stock to release or insufficient reserved stock")
    }
    
    return nil
}