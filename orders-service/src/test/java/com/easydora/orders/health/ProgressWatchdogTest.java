package com.easydora.orders.health;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProgressWatchdog answers one question only: has this service's messaging
 * loop (listener container idle ticks, consumer recovery attempts, or
 * successfully processed messages) made any progress recently? It never
 * asks whether RabbitMQ is currently reachable -- a stalled loop and a
 * broker that is merely down are different questions, and conflating them
 * would make the liveness probe restart every consumer during an ordinary,
 * tolerated broker outage (ADR-0038), turning an external outage into a
 * restart storm.
 */
class ProgressWatchdogTest {

    @Test
    void isNotStuckImmediatelyAfterConstruction() {
        ProgressWatchdog watchdog = new ProgressWatchdog(Clock.systemUTC());

        assertThat(watchdog.isStuck(Duration.ofMinutes(2))).isFalse();
    }

    @Test
    void isStuckOnceTheThresholdElapsesWithNoProgressRecorded() {
        Instant start = Instant.parse("2026-07-17T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        ProgressWatchdog watchdog = new ProgressWatchdog(clock);

        clock.advance(Duration.ofMinutes(5));

        assertThat(watchdog.isStuck(Duration.ofMinutes(2))).isTrue();
    }

    @Test
    void recordingProgressResetsTheStuckClock() {
        Instant start = Instant.parse("2026-07-17T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        ProgressWatchdog watchdog = new ProgressWatchdog(clock);

        clock.advance(Duration.ofMinutes(5));
        watchdog.recordProgress();

        assertThat(watchdog.isStuck(Duration.ofMinutes(2))).isFalse();
    }

    @Test
    void toleratesAnArbitrarilyLongBrokerOutageAsLongAsRecoveryAttemptsKeepBeingRecorded() {
        Instant start = Instant.parse("2026-07-17T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        ProgressWatchdog watchdog = new ProgressWatchdog(clock);

        // Simulates ListenerContainerConsumerFailedEvent firing every 5s for
        // 15 minutes straight while the broker is down -- each attempt is
        // itself progress, so the watchdog must never trip during this.
        for (int i = 0; i < 180; i++) {
            clock.advance(Duration.ofSeconds(5));
            watchdog.recordProgress();
        }

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
