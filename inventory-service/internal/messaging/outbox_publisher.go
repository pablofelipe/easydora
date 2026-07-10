package messaging

import (
	"context"
	"fmt"
	"easydora/correlation-commons"
	"inventory-service/internal/repository"
	"log"
	"os"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

var outboxLogger = correlation.NewLogger(os.Stdout, "inventory-service")

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
	channel *amqp.Channel
	repo    repository.InventoryRepository
	stop    chan struct{}
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
		channel: channel,
		repo:    repo,
		stop:    make(chan struct{}),
	}

	go publisher.run()
	return publisher, nil
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
	events, err := p.repo.FindUnpublishedOutboxEvents()
	if err != nil {
		log.Printf("[OUTBOX] Failed to query unpublished events: %v", err)
		return
	}

	for _, event := range events {
		correlationID, messageID, body, err := correlation.UnwrapOutboxPayload(event.Payload)
		if err != nil {
			log.Printf("[OUTBOX] Failed to decode envelope for event id=%d — will retry next poll: %v", event.ID, err)
			continue
		}

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
			log.Printf("[OUTBOX] Failed to publish event id=%d to %s/%s — will retry next poll: %v",
				event.ID, event.Exchange, event.RoutingKey, err)
			continue
		}

		if err := p.repo.MarkOutboxEventPublished(event.ID); err != nil {
			log.Printf("[OUTBOX] Failed to mark event id=%d as published: %v", event.ID, err)
			continue
		}

		ctx := correlation.WithMessageID(correlation.WithCorrelationID(context.Background(), correlationID), messageID)
		correlation.Info(outboxLogger, ctx, "outbox event published",
			"event", event.RoutingKey, "aggregateId", event.ID)
	}
}

// Stop stops the polling goroutine and closes the dedicated channel.
func (p *OutboxPublisher) Stop() {
	close(p.stop)
	p.channel.Close()
}
