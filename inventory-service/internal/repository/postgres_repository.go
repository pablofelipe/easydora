package repository

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"easydora/correlation-commons"
	"inventory-service/internal/models"
	"log"
	"time"
)

type InventoryRepository interface {
    GetByProductID(productID string) (*models.Inventory, error)
    GetAvailableByProductID(productID string) (*models.Inventory, error)
    UpdateQuantity(productID string, newQuantity int) error
    ReserveStockForOrder(ctx context.Context, command *models.ReserveStockCommand) (success bool, insufficientEvent *models.StockInsufficientEvent, err error)
    ReleaseStock(productID string, quantity int) error
    DeactivateProduct(productID string) error
    DeleteProduct(productID string) error
    IsProductAvailable(productID string) (bool, error)
    CreateInventory(productID string, quantity int) error
    FindUnpublishedOutboxEvents() ([]models.OutboxEvent, error)
    MarkOutboxEventPublished(id int64) error
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
    log.Printf("Attempting UPSERT inventory - product_id: %s, quantity: %d", productID, newQuantity)
    
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
        log.Printf("Error during UPSERT: %v", err)
        return fmt.Errorf("failed to upsert inventory: %v", err)
    }
    
    rows, err := result.RowsAffected()
    if err != nil {
        return fmt.Errorf("failed to get rows affected: %v", err)
    }
    
    log.Printf("UPSERT completed - Rows affected: %d", rows)
    return nil
}

// ReserveStockForOrder reserves every item of a ReserveStockCommand in a
// single Postgres transaction and, before committing, writes exactly one
// outbox_events row recording the outcome (stock.reserved on success,
// stock.insufficient on the first item that can't be reserved). This is
// what gives the Outbox Pattern its atomicity guarantee (mirrors
// auth-service's OutboxPublisher decisions): the stock change and the
// event that will eventually be published either both commit or neither
// does. A failure on any item rolls back the whole transaction, so an
// order's reservation is all-or-nothing across its items — no more
// partially-reserved orders left behind on failure.
func (r *PostgresRepository) ReserveStockForOrder(ctx context.Context, command *models.ReserveStockCommand) (bool, *models.StockInsufficientEvent, error) {
    tx, err := r.db.Begin()
    if err != nil {
        return false, nil, fmt.Errorf("failed to begin transaction: %v", err)
    }
    defer tx.Rollback()

    // Database-level idempotency check (closes the post-TTL gap left open
    // by inventoryService's in-memory cache, see README Roadmap): a
    // placeholder row is inserted for this OrderID and then locked with
    // FOR UPDATE. If another transaction (same process after its cache
    // entry expired, or a different process entirely) already resolved
    // this OrderID, the INSERT no-ops and the SELECT below returns that
    // outcome instead of reserving stock again. If a concurrent
    // transaction is mid-flight for the same OrderID, this SELECT blocks
    // on its row lock until that transaction commits or rolls back.
    outcome, err := r.lockReservationOutcome(tx, command.OrderID)
    if err != nil {
        return false, nil, err
    }
    if outcome != nil {
        return outcome.success, outcome.insufficientEvent, nil
    }

    for _, item := range command.Items {
        var currentQuantity, reserved int
        var available, deleted bool

        err = tx.QueryRow(`
            SELECT quantity, reserved, available, deleted
            FROM inventory_schema.inventory
            WHERE product_id = $1 FOR UPDATE`,
            item.ProductID,
        ).Scan(&currentQuantity, &reserved, &available, &deleted)

        if err != nil {
            return false, nil, fmt.Errorf("failed to get inventory for update: %v", err)
        }

        availableStock := currentQuantity - reserved
        if !available || deleted || availableStock < item.Quantity {
            insufficientEvent := &models.StockInsufficientEvent{
                OrderID:   command.OrderID,
                ProductID: item.ProductID,
                Required:  item.Quantity,
                Available: availableStock,
                Timestamp: time.Now(),
            }
            if err := r.insertOutboxEvent(ctx, tx, "order.exchange", "stock.insufficient", insufficientEvent); err != nil {
                return false, nil, err
            }
            if _, err := tx.Exec(
                `UPDATE inventory_schema.reservation_outcomes
                 SET success = false, insufficient_product_id = $2, insufficient_required = $3, insufficient_available = $4
                 WHERE order_id = $1`,
                command.OrderID, insufficientEvent.ProductID, insufficientEvent.Required, insufficientEvent.Available,
            ); err != nil {
                return false, nil, fmt.Errorf("failed to record reservation outcome: %v", err)
            }
            if err := tx.Commit(); err != nil {
                return false, nil, fmt.Errorf("failed to commit transaction: %v", err)
            }
            return false, insufficientEvent, nil
        }

        _, err = tx.Exec(
            "UPDATE inventory_schema.inventory SET reserved = reserved + $1, updated_at = NOW() WHERE product_id = $2",
            item.Quantity, item.ProductID,
        )
        if err != nil {
            return false, nil, fmt.Errorf("failed to reserve stock: %v", err)
        }
    }

    reservedEvent := &models.StockReservedEvent{
        OrderID:   command.OrderID,
        Success:   true,
        Message:   "stock reserved",
        Timestamp: time.Now(),
    }
    if err := r.insertOutboxEvent(ctx, tx, "order.exchange", "stock.reserved", reservedEvent); err != nil {
        return false, nil, err
    }

    if _, err := tx.Exec(
        `UPDATE inventory_schema.reservation_outcomes SET success = true WHERE order_id = $1`,
        command.OrderID,
    ); err != nil {
        return false, nil, fmt.Errorf("failed to record reservation outcome: %v", err)
    }

    if err := tx.Commit(); err != nil {
        return false, nil, fmt.Errorf("failed to commit transaction: %v", err)
    }

    return true, nil, nil
}

// reservationOutcome is the durable, database-level record of a
// previously resolved ReserveStockCommand, mirroring
// inventoryService.reservationOutcome but sourced from
// inventory_schema.reservation_outcomes instead of an in-memory cache --
// see lockReservationOutcome.
type reservationOutcome struct {
    success           bool
    insufficientEvent *models.StockInsufficientEvent
}

// lockReservationOutcome inserts a placeholder row for orderID if one
// doesn't already exist, then locks it with SELECT ... FOR UPDATE. It
// returns nil if this is a fresh OrderID (the caller should proceed with
// the reservation and record its outcome before committing), or the
// already-resolved outcome if a prior transaction already committed one
// for this OrderID.
func (r *PostgresRepository) lockReservationOutcome(tx *sql.Tx, orderID string) (*reservationOutcome, error) {
    if _, err := tx.Exec(
        `INSERT INTO inventory_schema.reservation_outcomes (order_id) VALUES ($1) ON CONFLICT (order_id) DO NOTHING`,
        orderID,
    ); err != nil {
        return nil, fmt.Errorf("failed to record reservation attempt: %v", err)
    }

    var success sql.NullBool
    var insufficientProductID sql.NullString
    var insufficientRequired, insufficientAvailable sql.NullInt64
    if err := tx.QueryRow(
        `SELECT success, insufficient_product_id, insufficient_required, insufficient_available
         FROM inventory_schema.reservation_outcomes WHERE order_id = $1 FOR UPDATE`,
        orderID,
    ).Scan(&success, &insufficientProductID, &insufficientRequired, &insufficientAvailable); err != nil {
        return nil, fmt.Errorf("failed to read reservation outcome: %v", err)
    }

    if !success.Valid {
        // Fresh OrderID (or a placeholder from a crashed transaction that
        // never committed and was rolled back with it) -- proceed as a
        // real delivery.
        return nil, nil
    }

    if success.Bool {
        return &reservationOutcome{success: true}, nil
    }
    return &reservationOutcome{
        success: false,
        insufficientEvent: &models.StockInsufficientEvent{
            OrderID:   orderID,
            ProductID: insufficientProductID.String,
            Required:  int(insufficientRequired.Int64),
            Available: int(insufficientAvailable.Int64),
            Timestamp: time.Now(),
        },
    }, nil
}

// insertOutboxEvent stores the event's CorrelationId (reused from ctx, or
// freshly generated if the inbound command carried none) and a fresh
// MessageId alongside the raw payload, wrapped via
// correlation.WrapOutboxPayload -- an Outbox-internal representation only
// (see internal/correlation/envelope.go). OutboxPublisher unwraps this and
// promotes both to native AMQP properties at actual publish time, so the
// wire shape of the event body itself never changes.
func (r *PostgresRepository) insertOutboxEvent(ctx context.Context, tx *sql.Tx, exchange, routingKey string, payload any) error {
    body, err := json.Marshal(payload)
    if err != nil {
        return fmt.Errorf("failed to serialize outbox payload: %v", err)
    }

    envelope := correlation.WrapOutboxPayload(
        correlation.CurrentOrNewCorrelationID(ctx),
        correlation.NewID(),
        string(body),
    )

    _, err = tx.Exec(
        `INSERT INTO inventory_schema.outbox_events (exchange, routing_key, payload) VALUES ($1, $2, $3)`,
        exchange, routingKey, envelope,
    )
    if err != nil {
        return fmt.Errorf("failed to insert outbox event: %v", err)
    }

    return nil
}

func (r *PostgresRepository) FindUnpublishedOutboxEvents() ([]models.OutboxEvent, error) {
    query := `SELECT id, exchange, routing_key, payload, created_at, published_at
              FROM inventory_schema.outbox_events
              WHERE published_at IS NULL
              ORDER BY created_at ASC`

    rows, err := r.db.Query(query)
    if err != nil {
        return nil, fmt.Errorf("failed to query unpublished outbox events: %v", err)
    }
    defer rows.Close()

    var events []models.OutboxEvent
    for rows.Next() {
        var e models.OutboxEvent
        if err := rows.Scan(&e.ID, &e.Exchange, &e.RoutingKey, &e.Payload, &e.CreatedAt, &e.PublishedAt); err != nil {
            return nil, fmt.Errorf("failed to scan outbox event: %v", err)
        }
        events = append(events, e)
    }

    return events, nil
}

func (r *PostgresRepository) MarkOutboxEventPublished(id int64) error {
    _, err := r.db.Exec(`UPDATE inventory_schema.outbox_events SET published_at = NOW() WHERE id = $1`, id)
    if err != nil {
        return fmt.Errorf("failed to mark outbox event published: %v", err)
    }
    return nil
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
        return nil // Not an error if it's already deleted
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
        return nil // Not an error if it doesn't exist
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
            return false, nil // Product doesn't exist in inventory
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
        INSERT INTO inventory_schema.inventory (product_id, quantity, reserved, available, deleted, created_at, updated_at)
        VALUES ($1, $2, 0, true, false, NOW(), NOW())
        RETURNING id
    `
    
    var id string
    err := r.db.QueryRowContext(ctx, query, productID, quantity).Scan(&id)
    if err != nil {
        return fmt.Errorf("failed to create inventory: %w", err)
    }
    
    log.Printf("Inventory created - product: %s, quantity: %d, id: %s",
        productID, quantity, id)
    return nil
}