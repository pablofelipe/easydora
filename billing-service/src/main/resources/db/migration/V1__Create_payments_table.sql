CREATE SCHEMA IF NOT EXISTS billing_schema;

CREATE TABLE IF NOT EXISTS billing_schema.payments (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL,
    user_id BIGINT NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(255) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'FAILED')),
    transaction_id VARCHAR(255),
    failure_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    CONSTRAINT uk_payments_order_id UNIQUE (order_id),
    CONSTRAINT uk_payments_transaction_id UNIQUE (transaction_id)
);
