CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    role VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    email_verified BOOLEAN DEFAULT FALSE,
    email_verification_token VARCHAR(500),
    token_created_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP NULL
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_status ON users(status);
CREATE INDEX idx_users_verification_token ON users(email_verification_token);

INSERT INTO users (email, password_hash, first_name, last_name, role, status, email_verified) 
VALUES (
    'admin@easydora.com', 
    '$2a$12$pQrStUvWxYzAbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdef',
    'Admin', 
    'System', 
    'ADMIN', 
    'ACTIVE', 
    true
);