package com.easydora.authservice.service;

import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationConstants;
import com.easydora.correlation.OutboxEnvelope;
import com.easydora.correlation.OutboxEnvelopeCodec;
import com.easydora.authservice.entity.OutboxEvent;
import com.easydora.authservice.health.ProgressWatchdog;
import com.easydora.authservice.repository.OutboxEventRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
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
    private final Counter outboxEventsPublishedCounter;
    private final Timer outboxPublishLagTimer;
    private final ProgressWatchdog progressWatchdog;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, RabbitTemplate rabbitTemplate,
            MeterRegistry meterRegistry, ProgressWatchdog progressWatchdog) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.progressWatchdog = progressWatchdog;
        // Business metrics (ADR-0036/ADR-0037): infra-level metrics already
        // answer "is the system healthy"; these answer a question infra
        // can't -- how much of the outbox backlog is actually draining, and
        // how long an event waits between being written and being
        // published. Timer name has no explicit "_seconds" suffix -- the
        // Prometheus registry appends the base unit itself, same as the
        // auto-instrumented http_server_requests_seconds this project
        // already relies on elsewhere.
        this.outboxEventsPublishedCounter = meterRegistry.counter("outbox_events_published_total");
        this.outboxPublishLagTimer = meterRegistry.timer("outbox_publish_lag");
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        // Recorded once per tick, not once per successful publish -- see
        // orders-service's identical OutboxPublisher for the full rationale.
        progressWatchdog.recordProgress();
        List<OutboxEvent> pending = outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc();

        for (OutboxEvent event : pending) {
            OutboxEnvelope envelope;
            try {
                envelope = OutboxEnvelopeCodec.unwrap(event.getPayload());
            } catch (Exception e) {
                BusinessEventLog.error(logger, "outbox.envelope.decode_failed", event.getId(),
                        "Failed to decode outbox envelope — will retry next poll", e);
                continue;
            }

            MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY, envelope.correlationId());
            MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, envelope.messageId());
            try {
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setCorrelationId(envelope.correlationId());
                properties.setMessageId(envelope.messageId());
                Message message = new Message(envelope.body().getBytes(StandardCharsets.UTF_8), properties);

                rabbitTemplate.send(event.getExchange(), event.getRoutingKey(), message);

                event.markPublished();
                outboxEventRepository.save(event);

                outboxEventsPublishedCounter.increment();
                outboxPublishLagTimer.record(Duration.between(event.getCreatedAt(), LocalDateTime.now()));
                BusinessEventLog.info(logger, event.getRoutingKey() + ".outbox.published", event.getId(),
                        "Outbox event published");
            } catch (Exception e) {
                BusinessEventLog.error(logger, event.getRoutingKey() + ".outbox.publish_failed", event.getId(),
                        "Outbox event publish failed — will retry next poll", e);
            } finally {
                MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
                MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
            }
        }
    }
}
