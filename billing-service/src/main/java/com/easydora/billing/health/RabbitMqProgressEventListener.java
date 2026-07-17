package com.easydora.billing.health;

import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerIdleEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the ProgressWatchdog from the two Spring AMQP events that fire while
 * a listener container's internal loop is doing its job -- see
 * orders-service's identical listener.
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
