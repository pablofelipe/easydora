package models

import "time"

// OutboxEvent is a row in inventory_schema.outbox_events: an event
// written atomically with the business state change it documents,
// waiting to be picked up and published to RabbitMQ by the outbox
// poller. PublishedAt is nil until the poller successfully publishes it.
type OutboxEvent struct {
	ID          int64
	Exchange    string
	RoutingKey  string
	Payload     string
	CreatedAt   time.Time
	PublishedAt *time.Time
}
