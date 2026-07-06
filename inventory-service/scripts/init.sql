CREATE SCHEMA IF NOT EXISTS inventory_schema;

SET search_path TO inventory_schema;

-- Create inventory table
CREATE TABLE IF NOT EXISTS inventory_schema.inventory (
    id VARCHAR(255) PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id VARCHAR(255) NOT NULL UNIQUE,
    quantity INTEGER NOT NULL DEFAULT 0,
    reserved INTEGER NOT NULL DEFAULT 0,
    available BOOLEAN NOT NULL DEFAULT true,
    deleted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

ALTER TABLE inventory_schema.inventory 
ADD COLUMN IF NOT EXISTS available BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE inventory_schema.inventory 
ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT false;

-- Create index for better performance
CREATE INDEX IF NOT EXISTS idx_inventory_product_id ON inventory_schema.inventory(product_id);

CREATE INDEX IF NOT EXISTS idx_inventory_available ON inventory_schema.inventory(available);
CREATE INDEX IF NOT EXISTS idx_inventory_deleted ON inventory_schema.inventory(deleted);

-- Outbox pattern (ADR-0007): written atomically with the stock
-- reservation transaction that produces it; published and marked by a
-- separate poller. Mirrors auth-service's outbox_events table.
CREATE TABLE IF NOT EXISTS inventory_schema.outbox_events (
    id BIGSERIAL PRIMARY KEY,
    exchange VARCHAR(200) NOT NULL,
    routing_key VARCHAR(200) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL
);

CREATE INDEX IF NOT EXISTS idx_outbox_events_unpublished ON inventory_schema.outbox_events(created_at) WHERE published_at IS NULL;
