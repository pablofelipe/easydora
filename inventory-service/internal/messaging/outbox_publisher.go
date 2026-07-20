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

// StartOutboxPublisher starts polling for unpublished outbox events in a
// background goroutine, opening its own channel (in publisher-confirm
// mode, via ensureChannel) on this consumer's connection.
func (r *RabbitMQConsumer) StartOutboxPublisher(repo repository.InventoryRepository) (*OutboxPublisher, error) {
	publisher := &OutboxPublisher{
		consumer: r,
		repo:     repo,
		stop:     make(chan struct{}),
	}

	if err := publisher.ensureChannel(); err != nil {
		return nil, fmt.Errorf("failed to create channel for outbox publisher: %w", err)
	}

	go publisher.run()
	return publisher, nil
}

// ensureChannel replaces the cached channel with a fresh one from the
// current (possibly reconnected) connection if the cached one has gone
// stale -- without this, every Publish attempt after a broker restart
// would fail identically forever, on the same dead channel, regardless of
// watchConnection having already reconnected the underlying connection.
// Every channel this returns is in publisher-confirm mode: publishPending
// relies on the broker's own acknowledgement, not Publish's fire-and-forget
// return, to decide whether an event actually reached the broker before
// marking it published -- without confirms, a publish against a target
// that does not exist (e.g. an exchange the broker lost and has not
// redeclared yet) still returns a nil error client-side, silently losing
// the event despite this file's own at-least-once delivery guarantee.
func (p *OutboxPublisher) ensureChannel() error {
	if p.channel != nil && !p.channel.IsClosed() {
		return nil
	}
	channel, err := p.consumer.createChannel()
	if err != nil {
		return fmt.Errorf("failed to refresh outbox publisher channel: %w", err)
	}
	if err := channel.Confirm(false); err != nil {
		channel.Close()
		return fmt.Errorf("failed to enable publisher confirms: %w", err)
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

		// A publish that the broker rejects at the protocol level (e.g. a
		// row targeting an exchange that genuinely does not exist) closes
		// the whole channel, not just that one message -- reusing a dead
		// channel for every event still left in this batch would fail
		// every one of them with "channel/connection is not open",
		// regardless of whether their own target exchange is fine. One bad
		// row must not be able to block the rest of the batch.
		if err := p.ensureChannel(); err != nil {
			correlation.Error(outboxLogger, ctx, "outbox publisher has no usable channel -- will retry next poll",
				"event", event.RoutingKey, "aggregateId", event.ID, "error", err.Error())
			continue
		}

		confirmation, err := p.channel.PublishWithDeferredConfirmWithContext(
			context.Background(),
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

		// Blocks until the broker acks or nacks this specific message, or
		// until the channel closes (e.g. the broker rejected the publish
		// because the exchange does not exist) -- either way this always
		// resolves, it does not hang forever. Only a positive ack means
		// the event is durably the broker's responsibility now.
		if ok := confirmation.Wait(); !ok {
			correlation.Error(outboxLogger, ctx, "outbox event publish was not confirmed by the broker -- will retry next poll",
				"event", event.RoutingKey, "aggregateId", event.ID)
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
