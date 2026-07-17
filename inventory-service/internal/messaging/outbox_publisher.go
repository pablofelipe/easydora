package messaging

import (
	"context"
	"easydora/correlation-commons"
	"fmt"
	"inventory-service/internal/repository"
	"os"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	amqp "github.com/rabbitmq/amqp091-go"
)

var outboxLogger = correlation.NewLogger(os.Stdout, "inventory-service")

// Business metrics (ADR-0036/ADR-0037): infra-level metrics already answer
// "is the system healthy"; these answer a question infra can't -- how much
// of the outbox backlog is actually draining, and how long an event waits
// between being written and being published.
var outboxEventsPublishedCounter = promauto.NewCounter(prometheus.CounterOpts{
	Name: "outbox_events_published_total",
	Help: "Total outbox events successfully published to RabbitMQ.",
})

var outboxPublishLagSeconds = promauto.NewHistogram(prometheus.HistogramOpts{
	Name:    "outbox_publish_lag_seconds",
	Help:    "Time between an outbox row being created and successfully published.",
	Buckets: prometheus.DefBuckets,
})

// outboxPollInterval mirrors auth-service's OutboxPublisher
// (@Scheduled(fixedDelay = 5000)) — same polling cadence, same decisions.
const outboxPollInterval = 5 * time.Second

// OutboxPublisher polls inventory_schema.outbox_events for rows not yet
// published and sends them to RabbitMQ. This is the only place that
// publishes an outbox-backed event — the transaction that wrote the row
// (see PostgresRepository.ReserveStockForOrder) never talks to RabbitMQ
// directly, so a reservation can never commit without its event being
// durably recorded, and vice versa. A row is marked published only after
// the broker publish returns without error; otherwise it's left untouched
// and retried on the next poll — at-least-once delivery, never lost.
// Mirrors auth-service's OutboxPublisher decisions (see
// auth-service/.../service/OutboxPublisher.java).
type OutboxPublisher struct {
	channel  *amqp.Channel
	consumer *RabbitMQConsumer
	repo     repository.InventoryRepository
	stop     chan struct{}
}

// StartOutboxPublisher creates a dedicated channel on this consumer's
// connection and starts polling for unpublished outbox events in a
// background goroutine.
func (r *RabbitMQConsumer) StartOutboxPublisher(repo repository.InventoryRepository) (*OutboxPublisher, error) {
	channel, err := r.createChannel()
	if err != nil {
		return nil, fmt.Errorf("failed to create channel for outbox publisher: %w", err)
	}

	publisher := &OutboxPublisher{
		channel:  channel,
		consumer: r,
		repo:     repo,
		stop:     make(chan struct{}),
	}

	go publisher.run()
	return publisher, nil
}

// ensureChannel replaces the cached channel with a fresh one from the
// current (possibly reconnected) connection if the cached one has gone
// stale -- without this, every Publish attempt after a broker restart
// would fail identically forever, on the same dead channel, regardless of
// watchConnection having already reconnected the underlying connection.
func (p *OutboxPublisher) ensureChannel() error {
	if p.channel != nil && !p.channel.IsClosed() {
		return nil
	}
	channel, err := p.consumer.createChannel()
	if err != nil {
		return fmt.Errorf("failed to refresh outbox publisher channel: %w", err)
	}
	p.channel = channel
	return nil
}

func (p *OutboxPublisher) run() {
	ticker := time.NewTicker(outboxPollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			p.publishPending()
		case <-p.stop:
			return
		}
	}
}

func (p *OutboxPublisher) publishPending() {
	// Recorded once per tick, not once per successful publish -- this
	// loop being alive and polling is progress regardless of whether the
	// broker accepts anything this cycle. See the Java services'
	// identical OutboxPublisher for the full rationale.
	p.consumer.Watchdog.RecordProgress()

	if err := p.ensureChannel(); err != nil {
		correlation.Error(outboxLogger, context.Background(), "outbox publisher has no usable channel -- will retry next poll",
			"error", err.Error())
		return
	}

	events, err := p.repo.FindUnpublishedOutboxEvents()
	if err != nil {
		correlation.Error(outboxLogger, context.Background(), "failed to query unpublished outbox events",
			"error", err.Error())
		return
	}

	for _, event := range events {
		correlationID, messageID, body, err := correlation.UnwrapOutboxPayload(event.Payload)
		if err != nil {
			correlation.Error(outboxLogger, context.Background(), "failed to decode outbox envelope -- will retry next poll",
				"aggregateId", event.ID, "error", err.Error())
			continue
		}

		ctx := correlation.WithMessageID(correlation.WithCorrelationID(context.Background(), correlationID), messageID)

		err = p.channel.Publish(
			event.Exchange,
			event.RoutingKey,
			false, // mandatory
			false, // immediate
			amqp.Publishing{
				ContentType:   "application/json",
				CorrelationId: correlationID,
				MessageId:     messageID,
				Body:          []byte(body),
			},
		)
		if err != nil {
			correlation.Error(outboxLogger, ctx, "failed to publish outbox event -- will retry next poll",
				"event", event.RoutingKey, "aggregateId", event.ID, "error", err.Error())
			continue
		}

		if err := p.repo.MarkOutboxEventPublished(event.ID); err != nil {
			correlation.Error(outboxLogger, ctx, "failed to mark outbox event as published",
				"event", event.RoutingKey, "aggregateId", event.ID, "error", err.Error())
			continue
		}

		outboxEventsPublishedCounter.Inc()
		outboxPublishLagSeconds.Observe(time.Since(event.CreatedAt).Seconds())
		correlation.Info(outboxLogger, ctx, "outbox event published",
			"event", event.RoutingKey, "aggregateId", event.ID)
	}
}

// Stop stops the polling goroutine and closes the dedicated channel.
func (p *OutboxPublisher) Stop() {
	close(p.stop)
	p.channel.Close()
}
