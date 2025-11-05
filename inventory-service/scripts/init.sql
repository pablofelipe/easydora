CREATE SCHEMA IF NOT EXISTS inventory_schema;

SET search_path TO inventory_schema;

-- Create inventory table
CREATE TABLE IF NOT EXISTS inventory_schema.inventory (
    id VARCHAR(255) PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id VARCHAR(255) NOT NULL UNIQUE,
    quantity INTEGER NOT NULL DEFAULT 0,
    reserved INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create index for better performance
CREATE INDEX IF NOT EXISTS idx_inventory_product_id ON inventory_schema.inventory(product_id);
