package com.easydora.authservice.service;

import com.easydora.authservice.entity.OutboxEvent;
import com.easydora.authservice.repository.OutboxEventRepository;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

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
 * Unit-level proof that OutboxPublisher never loses a row when the broker
 * is unreachable at poll time: the row stays unpublished (published_at
 * null) after a failed send, and the next poll retries the same row and
 * succeeds once the broker is back. Both collaborators are mocked here —
 * this is about the poller's own retry logic, not broker/DB wiring, which
 * VerifyEmailOutboxIntegrationTest and VerifyEmailOutboxHappyPathIntegrationTest
 * already cover against a real RabbitMQ.
 */
class OutboxPublisherRetryTest {

    @Test
    void pendingEventStaysUnpublishedUntilBrokerAcceptsIt() {
        OutboxEvent event = new OutboxEvent("auth.exchange", "user.verified", "555");
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        when(outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(event));

        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doThrow(new RuntimeException("broker unreachable"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        OutboxPublisher publisher = new OutboxPublisher(outboxEventRepository, rabbitTemplate);

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
}
