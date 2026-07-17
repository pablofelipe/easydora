package com.easydora.orders.health;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerIdleEvent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Both events fire while the listener container's internal loop is doing
 * its job -- one during normal idle ticks, the other while it is actively
 * retrying a broken connection (Spring AMQP's own recovery loop, already
 * proven to work in ADR-0038/the 2026-07-17 live verification) -- so both
 * must count as progress.
 */
class RabbitMqProgressEventListenerTest {

    @Test
    void idleEventCountsAsProgress() {
        ProgressWatchdog watchdog = mock(ProgressWatchdog.class);
        RabbitMqProgressEventListener listener = new RabbitMqProgressEventListener(watchdog);

        listener.onIdle(mock(ListenerContainerIdleEvent.class));

        verify(watchdog).recordProgress();
    }

    @Test
    void consumerFailedEventCountsAsProgress() {
        ProgressWatchdog watchdog = mock(ProgressWatchdog.class);
        RabbitMqProgressEventListener listener = new RabbitMqProgressEventListener(watchdog);

        listener.onConsumerFailed(mock(ListenerContainerConsumerFailedEvent.class));

        verify(watchdog).recordProgress();
    }
}
