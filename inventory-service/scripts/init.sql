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
