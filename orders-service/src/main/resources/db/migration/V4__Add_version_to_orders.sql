-- Optimistic locking (ADR-0033): Order is updated from both HTTP requests
-- and RabbitMQ consumers, so concurrent writes to the same row must be
-- detected instead of silently lost.
ALTER TABLE orders_schema.orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
