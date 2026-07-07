CREATE SCHEMA IF NOT EXISTS notification_schema;

-- Processed notifications: modeled generically (event, aggregate id,
-- status, payload, timestamps) rather than coupled to the concept of
-- email, since this stage's delivery mechanism is a fake and future
-- stages may add other channels (SMS, push) without changing this shape.
CREATE TABLE IF NOT EXISTS notification_schema.notifications (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notifications_aggregate_id ON notification_schema.notifications(aggregate_id);
