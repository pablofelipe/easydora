package com.easydora.orders.health;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Answers one question only: has this service's messaging loop made any
 * progress recently -- a message processed, a listener container idle tick,
 * or a consumer recovery attempt (successful or not)? Deliberately does not
 * ask whether RabbitMQ is reachable right now: a stalled loop and a broker
 * that is merely down (and being tolerated, per ADR-0038) are different
 * questions. Conflating them would make a liveness probe built on this
 * watchdog restart every consumer during an ordinary broker outage, turning
 * an external dependency's downtime into a self-inflicted restart storm.
 */
@Component
public class ProgressWatchdog {

    private final Clock clock;
    private volatile Instant lastProgress;

    public ProgressWatchdog(Clock clock) {
        this.clock = clock;
        this.lastProgress = clock.instant();
    }

    public void recordProgress() {
        lastProgress = clock.instant();
    }

    public boolean isStuck(Duration threshold) {
        return Duration.between(lastProgress, clock.instant()).compareTo(threshold) > 0;
    }
}
