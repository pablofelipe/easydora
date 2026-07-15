CREATE TABLE billing_schema.outbox_events (
    id BIGSERIAL PRIMARY KEY,
    exchange VARCHAR(200) NOT NULL,
    routing_key VARCHAR(200) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL
);

CREATE INDEX idx_billing_outbox_events_unpublished ON billing_schema.outbox_events(created_at) WHERE published_at IS NULL;
