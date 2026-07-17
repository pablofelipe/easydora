package com.easydora.orders.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bean name resolves to indicator id "rabbitMqProgress" (Spring Boot's
 * {name}HealthIndicator -> {name} convention), included in the actuator
 * "liveness" group (see application.properties) instead of the default
 * aggregate health -- this DOWN means "this process's messaging loop is
 * genuinely stalled", never "RabbitMQ happens to be unreachable right now",
 * which is the distinction the whole ProgressWatchdog design exists for
 * (see docs/adr/0038-infrastructure-startup-resilience.md's Update).
 */
@Component
public class RabbitMqProgressHealthIndicator implements HealthIndicator {

    // Generous relative to how often progress is actually recorded here
    // (idle events every 30s, consumer-recovery attempts every ~5s, outbox
    // ticks every 5s) -- this only trips on a genuine stall, many multiples
    // longer than any single one of those intervals.
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(5);

    private final ProgressWatchdog watchdog;

    public RabbitMqProgressHealthIndicator(ProgressWatchdog watchdog) {
        this.watchdog = watchdog;
    }

    @Override
    public Health health() {
        if (watchdog.isStuck(STUCK_THRESHOLD)) {
            return Health.down()
                    .withDetail("reason", "no messaging progress recorded within " + STUCK_THRESHOLD)
                    .build();
        }
        return Health.up().build();
    }
}
