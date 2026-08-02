-- Persists why a refund compensation failed (ADR-0034's REFUND_FAILED),
-- previously only logged. Needed by the new admin remediation queue
-- (GET /refunds/failed) so an operator reviewing the list can see the
-- reason without cross-referencing logs.
ALTER TABLE orders_schema.orders ADD COLUMN refund_failure_reason VARCHAR(500);
