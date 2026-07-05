package repository

import (
	"context"
	"database/sql"
	"fmt"
	"inventory-service/internal/models"
	"log"
	"time"
)

type InventoryRepository interface {
    GetByProductID(productID string) (*models.Inventory, error)
    GetAvailableByProductID(productID string) (*models.Inventory, error)
    UpdateQuantity(productID string, newQuantity int) error
    ReserveStock(productID string, quantity int) error
    ReleaseStock(productID string, quantity int) error
    DeactivateProduct(productID string) error
    DeleteProduct(productID string) error
    IsProductAvailable(productID string) (bool, error)
    CreateInventory(productID string, quantity int) error
}

type PostgresRepository struct {
    db *sql.DB
}

func NewPostgresRepository(db *sql.DB) *PostgresRepository {
    return &PostgresRepository{db: db}
}

func (r *PostgresRepository) GetByProductID(productID string) (*models.Inventory, error) {
    query := `SELECT id, product_id, quantity, reserved, available, deleted, created_at, updated_at 
              FROM inventory_schema.inventory WHERE product_id = $1`
    
    var inv models.Inventory
    err := r.db.QueryRow(query, productID).Scan(
        &inv.ID, &inv.ProductID, &inv.Quantity, &inv.Reserved,
        &inv.Available, &inv.Deleted,
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

func (r *PostgresRepository) GetAvailableByProductID(productID string) (*models.Inventory, error) {
    query := `SELECT id, product_id, quantity, reserved, available, deleted, created_at, updated_at 
              FROM inventory_schema.inventory 
              WHERE product_id = $1 AND available = true AND deleted = false`
    
    var inv models.Inventory
    err := r.db.QueryRow(query, productID).Scan(
        &inv.ID, &inv.ProductID, &inv.Quantity, &inv.Reserved,
        &inv.Available, &inv.Deleted,
        &inv.CreatedAt, &inv.UpdatedAt,
    )
    
    if err == sql.ErrNoRows {
        return nil, fmt.Errorf("product not available or not found: %s", productID)
    }
    if err != nil {
        return nil, fmt.Errorf("failed to get available inventory: %v", err)
    }
    
    return &inv, nil
}

func (r *PostgresRepository) UpdateQuantity(productID string, newQuantity int) error {
    log.Printf("Tentando UPSERT inventory - product_id: %s, quantity: %d", productID, newQuantity)
    
    query := `
        INSERT INTO inventory_schema.inventory (product_id, quantity, available, deleted) 
        VALUES ($1, $2, true, false)
        ON CONFLICT (product_id) 
        DO UPDATE SET 
            quantity = $2,
            available = true,
            deleted = false,
            updated_at = NOW()
        WHERE inventory_schema.inventory.product_id = $1
    `
    
    result, err := r.db.Exec(query, productID, newQuantity)
    if err != nil {
        log.Printf("Erro no UPSERT: %v", err)
        return fmt.Errorf("failed to upsert inventory: %v", err)
    }
    
    rows, err := result.RowsAffected()
    if err != nil {
        return fmt.Errorf("failed to get rows affected: %v", err)
    }
    
    log.Printf("UPSERT realizado - Rows affected: %d", rows)
    return nil
}

func (r *PostgresRepository) ReserveStock(productID string, quantity int) error {
    // Use transaction to ensure atomicity
    tx, err := r.db.Begin()
    if err != nil {
        return fmt.Errorf("failed to begin transaction: %v", err)
    }
    defer tx.Rollback()

    var currentQuantity, reserved int
    var available, deleted bool
    
    err = tx.QueryRow(`
        SELECT quantity, reserved, available, deleted 
        FROM inventory_schema.inventory 
        WHERE product_id = $1 FOR UPDATE`,
        productID,
    ).Scan(&currentQuantity, &reserved, &available, &deleted)
    
    if err != nil {
        return fmt.Errorf("failed to get inventory for update: %v", err)
    }
    
    if !available || deleted {
        return fmt.Errorf("product not available for reservation: %s (available: %v, deleted: %v)", 
            productID, available, deleted)
    }

    availableStock := currentQuantity - reserved
    if availableStock < quantity {
        return fmt.Errorf("insufficient stock: available %d, required %d", availableStock, quantity)
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
    query := `UPDATE inventory_schema.inventory 
              SET reserved = reserved - $1, updated_at = NOW() 
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

func (r *PostgresRepository) DeactivateProduct(productID string) error {
    query := `UPDATE inventory_schema.inventory 
              SET available = false, updated_at = NOW()
              WHERE product_id = $1 AND deleted = false`
    
    result, err := r.db.Exec(query, productID)
    if err != nil {
        log.Printf("Error deactivating product %s: %v", productID, err)
        return fmt.Errorf("failed to deactivate product: %v", err)
    }
    
    rows, err := result.RowsAffected()
    if err != nil {
        return fmt.Errorf("failed to get rows affected: %v", err)
    }
    
    if rows == 0 {
        log.Printf("Product %s not found or already deleted", productID)
        return nil // Não é erro se já estiver deletado
    }
    
    log.Printf("Product deactivated in inventory: %s", productID)
    return nil
}

func (r *PostgresRepository) DeleteProduct(productID string) error {
    query := `UPDATE inventory_schema.inventory 
              SET deleted = true, available = false, updated_at = NOW()
              WHERE product_id = $1`
    
    result, err := r.db.Exec(query, productID)
    if err != nil {
        log.Printf("Error deleting product %s from inventory: %v", productID, err)
        return fmt.Errorf("failed to delete product: %v", err)
    }
    
    rows, err := result.RowsAffected()
    if err != nil {
        return fmt.Errorf("failed to get rows affected: %v", err)
    }
    
    if rows == 0 {
        log.Printf("Product %s not found in inventory", productID)
        return nil // Não é erro se não existir
    }
    
    log.Printf("Product marked as deleted in inventory: %s", productID)
    return nil
}

func (r *PostgresRepository) IsProductAvailable(productID string) (bool, error) {
    var available, deleted bool
    
    query := `SELECT available, deleted 
              FROM inventory_schema.inventory 
              WHERE product_id = $1`
    
    err := r.db.QueryRow(query, productID).Scan(&available, &deleted)
    if err != nil {
        if err == sql.ErrNoRows {
            return false, nil // Produto não existe no inventory
        }
        return false, fmt.Errorf("failed to check availability: %v", err)
    }
    
    return available && !deleted, nil
}

func (r *PostgresRepository) GetAvailableProducts() ([]models.Inventory, error) {
    query := `SELECT id, product_id, quantity, reserved, available, deleted, created_at, updated_at 
              FROM inventory_schema.inventory 
              WHERE available = true AND deleted = false
              ORDER BY product_id`
    
    rows, err := r.db.Query(query)
    if err != nil {
        return nil, fmt.Errorf("failed to get available products: %v", err)
    }
    defer rows.Close()
    
    var inventories []models.Inventory
    for rows.Next() {
        var inv models.Inventory
        err := rows.Scan(
            &inv.ID, &inv.ProductID, &inv.Quantity, &inv.Reserved,
            &inv.Available, &inv.Deleted,
            &inv.CreatedAt, &inv.UpdatedAt,
        )
        if err != nil {
            return nil, fmt.Errorf("failed to scan inventory row: %v", err)
        }
        inventories = append(inventories, inv)
    }
    
    return inventories, nil
}

func (r *PostgresRepository) CreateInventory(productID string, quantity int) error {
    ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()
    
    query := `
        INSERT INTO inventory (product_id, quantity, reserved, is_active, created_at, updated_at)
        VALUES ($1, $2, 0, true, NOW(), NOW())
        RETURNING id
    `
    
    var id string
    err := r.db.QueryRowContext(ctx, query, productID, quantity).Scan(&id)
    if err != nil {
        return fmt.Errorf("failed to create inventory: %w", err)
    }
    
    log.Printf("Inventory criado - product: %s, quantity: %d, id: %s",
        productID, quantity, id)
    return nil
}