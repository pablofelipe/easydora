-- Tabela de buyers (similar à sellers do products-service)
CREATE TABLE orders_schema.buyers (
    user_id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    avatar_url VARCHAR(500),
    role VARCHAR(50) NOT NULL DEFAULT 'BUYER' CHECK (role IN ('BUYER', 'SELLER', 'ADMIN')),
    active BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX idx_buyers_active ON orders_schema.buyers(active);
CREATE INDEX idx_buyers_role ON orders_schema.buyers(role);
CREATE INDEX idx_buyers_email ON orders_schema.buyers(email);