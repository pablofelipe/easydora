package com.easydora.billing.health;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerIdleEvent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
