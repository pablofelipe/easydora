package com.easydora.products.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bean name resolves to indicator id "rabbitMqProgress", included in the
 * actuator "liveness" group (see application.properties) instead of the
 * default aggregate health -- see orders-service's identical indicator.
 */
@Component
public class RabbitMqProgressHealthIndicator implements HealthIndicator {

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
