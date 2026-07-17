package com.easydora.authservice.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RabbitMqProgressHealthIndicatorTest {

    @Test
    void upWhileTheWatchdogIsNotStuck() {
        ProgressWatchdog watchdog = mock(ProgressWatchdog.class);
        when(watchdog.isStuck(any(Duration.class))).thenReturn(false);

        Health health = new RabbitMqProgressHealthIndicator(watchdog).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downOnceTheWatchdogReportsStuck() {
        ProgressWatchdog watchdog = mock(ProgressWatchdog.class);
        when(watchdog.isStuck(any(Duration.class))).thenReturn(true);

        Health health = new RabbitMqProgressHealthIndicator(watchdog).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
