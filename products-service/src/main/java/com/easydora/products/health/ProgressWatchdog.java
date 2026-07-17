package com.easydora.products.health;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Answers one question only: has this service's messaging loop made any
 * progress recently -- a message processed, a listener container idle tick,
 * or a consumer recovery attempt? Deliberately does not ask whether
 * RabbitMQ is reachable right now (see orders-service's identical class and
 * docs/adr/0038-infrastructure-startup-resilience.md's Update).
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
