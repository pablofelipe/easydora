-- Optimistic locking (ADR-0033): a Payment can receive duplicated
-- callbacks/retries in a real payment gateway, so concurrent writes to
-- the same row must be detected instead of silently lost.
ALTER TABLE billing_schema.payments ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
