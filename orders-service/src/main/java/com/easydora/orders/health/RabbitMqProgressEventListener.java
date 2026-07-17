package com.easydora.orders.health;

import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerIdleEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the ProgressWatchdog from the two Spring AMQP events that fire while
 * a listener container's internal loop is doing its job, whether or not any
 * business message is currently flowing: ListenerContainerIdleEvent (normal
 * idle ticks, requires idleEventInterval to be set on the container
 * factory) and ListenerContainerConsumerFailedEvent (fires once per failed
 * consumer-restart attempt while Spring AMQP's own automatic recovery is
 * actively retrying a broken connection).
 */
@Component
public class RabbitMqProgressEventListener {

    private final ProgressWatchdog watchdog;

    public RabbitMqProgressEventListener(ProgressWatchdog watchdog) {
        this.watchdog = watchdog;
    }

    @EventListener
    public void onIdle(ListenerContainerIdleEvent event) {
        watchdog.recordProgress();
    }

    @EventListener
    public void onConsumerFailed(ListenerContainerConsumerFailedEvent event) {
        watchdog.recordProgress();
    }
}
