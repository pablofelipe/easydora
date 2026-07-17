package com.easydora.authservice.health;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors orders-service's ProgressWatchdog (docs/adr/0038's Update):
 * answers only "has this service's messaging loop made any progress
 * recently", never "is RabbitMQ reachable now" -- auth-service has no
 * @RabbitListener at all, so here progress comes exclusively from
 * OutboxPublisher's own poll tick.
 */
class ProgressWatchdogTest {

    @Test
    void isNotStuckImmediatelyAfterConstruction() {
        ProgressWatchdog watchdog = new ProgressWatchdog(Clock.systemUTC());

        assertThat(watchdog.isStuck(Duration.ofMinutes(2))).isFalse();
    }

    @Test
    void isStuckOnceTheThresholdElapsesWithNoProgressRecorded() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T10:00:00Z"));
        ProgressWatchdog watchdog = new ProgressWatchdog(clock);

        clock.advance(Duration.ofMinutes(5));

        assertThat(watchdog.isStuck(Duration.ofMinutes(2))).isTrue();
    }

    @Test
    void recordingProgressResetsTheStuckClock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T10:00:00Z"));
        ProgressWatchdog watchdog = new ProgressWatchdog(clock);

        clock.advance(Duration.ofMinutes(5));
        watchdog.recordProgress();

        assertThat(watchdog.isStuck(Duration.ofMinutes(2))).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
