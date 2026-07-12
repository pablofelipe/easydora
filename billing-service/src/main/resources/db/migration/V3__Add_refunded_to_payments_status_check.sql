ALTER TABLE billing_schema.payments DROP CONSTRAINT payments_status_check;
ALTER TABLE billing_schema.payments ADD CONSTRAINT payments_status_check CHECK (status IN ('PENDING', 'APPROVED', 'FAILED', 'REFUNDED'));
