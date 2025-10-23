CREATE SCHEMA IF NOT EXISTS orders_schema;

CREATE TABLE orders_schema.orders (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    state VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE orders_schema.order_items (
    id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price DECIMAL(10,2) NOT NULL CHECK (unit_price > 0),
    FOREIGN KEY (order_id) REFERENCES orders_schema.orders(id) ON DELETE CASCADE
);

CREATE INDEX idx_orders_user_id ON orders_schema.orders(user_id);
CREATE INDEX idx_orders_state ON orders_schema.orders(state);
CREATE INDEX idx_order_items_order_id ON orders_schema.order_items(order_id);