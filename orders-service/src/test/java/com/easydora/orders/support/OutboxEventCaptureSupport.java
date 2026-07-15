package com.easydora.orders.support;

import com.easydora.correlation.OutboxEnvelope;
import com.easydora.correlation.OutboxEnvelopeCodec;
import com.easydora.orders.entity.OutboxEvent;
import com.easydora.orders.repository.OutboxEventRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Test-only support for OrderService's outbox writes (ADR-0037/ADR-0034
 * Update) -- fills the role RecordingRabbitTemplate used to fill before
 * OrderService's four publishes moved from a direct RabbitTemplate call to
 * an OutboxEventRepository.save(...) in the same transaction. Captures the
 * saved rows and decodes their envelope body back into the original event
 * type, so tests can assert on the same fields they did before.
 */
public final class OutboxEventCaptureSupport {

    private OutboxEventCaptureSupport() {
    }

    public static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    public static List<OutboxEvent> capture(OutboxEventRepository outboxEventRepository) {
        List<OutboxEvent> saved = new ArrayList<>();
        // lenient: several tests using this capture assert that NO event
        // was written (e.g. a rejected write, an optimistic-locking
        // conflict) -- this stub is legitimately unused in those cases,
        // not a stale leftover Mockito's strict-stubs check should flag.
        lenient().when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            saved.add(event);
            return event;
        });
        return saved;
    }

    public static <T> T bodyAs(OutboxEvent event, Class<T> type) {
        try {
            OutboxEnvelope envelope = OutboxEnvelopeCodec.unwrap(event.getPayload());
            return objectMapper().readValue(envelope.body(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
