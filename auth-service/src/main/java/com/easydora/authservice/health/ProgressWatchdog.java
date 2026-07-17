package com.easydora.authservice.health;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Answers one question only: has this service's messaging loop made any
 * progress recently? auth-service has no @RabbitListener, so here progress
 * comes exclusively from OutboxPublisher's own poll tick (see
 * docs/adr/0038-infrastructure-startup-resilience.md's Update).
 * Deliberately does not ask whether RabbitMQ is reachable right now -- a
 * stalled loop and a broker that is merely down (and being tolerated, per
 * the same ADR) are different questions.
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
