CREATE SCHEMA IF NOT EXISTS products_schema AUTHORIZATION admin;

CREATE TABLE IF NOT EXISTS sellers (
    user_id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(500),
    role VARCHAR(50) NOT NULL DEFAULT 'SELLER' CHECK (role IN ('BUYER', 'SELLER', 'ADMIN')),
    active BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    
    CONSTRAINT uk_sellers_email UNIQUE (email)
);

CREATE INDEX IF NOT EXISTS idx_sellers_active ON sellers(active);
CREATE INDEX IF NOT EXISTS idx_sellers_role ON sellers(role);
CREATE INDEX IF NOT EXISTS idx_sellers_email ON sellers(email);