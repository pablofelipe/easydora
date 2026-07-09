package com.easydora.authservice.service;

import com.easydora.correlation.OutboxEnvelope;
import com.easydora.correlation.OutboxEnvelopeCodec;
import com.easydora.authservice.entity.OutboxEvent;
import com.easydora.authservice.repository.OutboxEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Polls outbox_events for rows not yet published and sends them to RabbitMQ.
 * This is the only place that publishes an outbox-backed event — the
 * business method that wrote the row (e.g. UserService.verifyEmail) commits
 * the outbox row in the same transaction as its own state change and never
 * talks to RabbitMQ directly, so a save failure can never leave an event
 * published without the matching data.
 *
 * A row is marked published only after RabbitTemplate.send returns without
 * throwing; if the broker is unavailable, the row is left untouched and
 * retried on the next poll — at-least-once delivery, never lost.
 */
@Component
public class OutboxPublisher {

    private static final Logger logger = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, RabbitTemplate rabbitTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc();

        for (OutboxEvent event : pending) {
            try {
                OutboxEnvelope envelope = OutboxEnvelopeCodec.unwrap(event.getPayload());

                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setCorrelationId(envelope.correlationId());
                properties.setMessageId(envelope.messageId());
                Message message = new Message(envelope.body().getBytes(StandardCharsets.UTF_8), properties);

                rabbitTemplate.send(event.getExchange(), event.getRoutingKey(), message);

                event.markPublished();
                outboxEventRepository.save(event);
            } catch (Exception e) {
                logger.error("Failed to publish outbox event id={} to {}/{} — will retry next poll",
                        event.getId(), event.getExchange(), event.getRoutingKey(), e);
            }
        }
    }
}
