package com.easydora.billing.service;

import com.easydora.correlation.OutboxEnvelopeCodec;
import com.easydora.billing.entity.OutboxEvent;
import com.easydora.billing.health.ProgressWatchdog;
import com.easydora.billing.repository.OutboxEventRepository;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level proof that billing-service's OutboxPublisher never loses a row
 * when the broker is unreachable at poll time -- mirrors auth-service's and
 * orders-service's own OutboxPublisherRetryTest (ADR-0037's consolidated
 * specification). Both collaborators are mocked: this is about the
 * poller's own retry logic, not broker/DB wiring.
 */
class OutboxPublisherRetryTest {

    @Test
    void pendingEventStaysUnpublishedUntilBrokerAcceptsIt() {
        OutboxEvent event = new OutboxEvent("order.exchange", "payment.approved",
                OutboxEnvelopeCodec.wrap("test-correlation-id", "test-message-id", null, "{\"orderId\":\"order-1\"}"));
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        when(outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(event));

        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doThrow(new RuntimeException("broker unreachable"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        OutboxPublisher publisher = new OutboxPublisher(outboxEventRepository, rabbitTemplate,
                new SimpleMeterRegistry(), mock(ProgressWatchdog.class), io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);

        publisher.publishPendingEvents();

        assertThat(event.getPublishedAt())
                .withFailMessage("event should remain unpublished after a failed send")
                .isNull();
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));

        doNothing().when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        publisher.publishPendingEvents();

        assertThat(event.getPublishedAt())
                .withFailMessage("event should be marked published once the broker accepts it")
                .isNotNull();
        verify(outboxEventRepository, times(1)).save(event);
    }

    @Test
    void outboxEventsPublishedCounterOnlyCountsRealPublishesNotRetriedFailures() {
        OutboxEvent event = new OutboxEvent("order.exchange", "payment.approved",
                OutboxEnvelopeCodec.wrap("test-correlation-id", "test-message-id", null, "{\"orderId\":\"order-1\"}"));
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        when(outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(event));

        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doThrow(new RuntimeException("broker unreachable"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OutboxPublisher publisher = new OutboxPublisher(outboxEventRepository, rabbitTemplate,
                meterRegistry, mock(ProgressWatchdog.class), io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);

        publisher.publishPendingEvents();

        assertThat(meterRegistry.get("outbox_events_published_total").counter().count()).isZero();

        doNothing().when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        publisher.publishPendingEvents();

        assertThat(meterRegistry.get("outbox_events_published_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("outbox_publish_lag").timer().count()).isEqualTo(1);
    }
}
