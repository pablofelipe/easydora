package com.easydora.billing.service;

import com.easydora.correlation.OutboxEnvelopeCodec;
import com.easydora.billing.entity.OutboxEvent;
import com.easydora.billing.health.ProgressWatchdog;
import com.easydora.billing.repository.OutboxEventRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The poll tick itself is progress, whether or not anything actually gets
 * published this cycle -- see orders-service's identical test for the full
 * rationale.
 */
class OutboxPublisherProgressTest {

    @Test
    void recordsProgressOnEveryPollTickEvenWhenPublishFails() {
        OutboxEvent event = new OutboxEvent("order.exchange", "payment.approved",
                OutboxEnvelopeCodec.wrap("test-correlation-id", "test-message-id", null, "{\"paymentId\":1}"));
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        when(outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(event));

        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doThrow(new RuntimeException("broker unreachable"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        ProgressWatchdog watchdog = mock(ProgressWatchdog.class);
        OutboxPublisher publisher = new OutboxPublisher(
                outboxEventRepository, rabbitTemplate, new SimpleMeterRegistry(), watchdog, io.micrometer.tracing.Tracer.NOOP, io.micrometer.tracing.propagation.Propagator.NOOP);

        publisher.publishPendingEvents();

        verify(watchdog).recordProgress();
    }
}
