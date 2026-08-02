-- Tracks the order's own state, as broadcast via order.status-changed
-- (orders-service), independent of Payment's own status. PaymentService
-- uses this to reject a processPayment call for an order that hasn't
-- actually reached INVENTORY_RESERVED yet, instead of accepting a charge
-- at any time and relying solely on the compensation saga (ADR-0034) to
-- unwind it after the fact.
ALTER TABLE billing_schema.payments ADD COLUMN order_state VARCHAR(50);
