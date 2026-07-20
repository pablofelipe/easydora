package com.easydora.orders.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves the reconnection observability contract (docs/adr/0038's Update):
 * rabbitmq_reconnect_attempts_total and rabbitmq_topology_setup_total{outcome}
 * exist in orders-service with the same names/shape as inventory-service's
 * (Go) equivalents, observed via Spring AMQP's own ConnectionListener rather
 * than reimplementing reconnection -- Automatic Connection Recovery +
 * Topology Recovery are already built into the framework (see the
 * investigation behind this ADR's Update). The very first onCreate is the
 * boot connection, not a reconnect, so it must not be counted.
 */
class RabbitMqReconnectionMetricsTest {

    @Test
    void firstConnectionIsTheBootConnectionAndIsNotCountedAsAReconnect() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RabbitMqReconnectionMetrics listener = new RabbitMqReconnectionMetrics(objectProviderOf(meterRegistry));

        listener.onCreate(mock(Connection.class));

        assertThat(meterRegistry.get("rabbitmq_reconnect_attempts_total").counter().count()).isZero();
    }

    @Test
    void aLaterOnCreateCountsAsASuccessfulReconnectAndTopologySetup() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RabbitMqReconnectionMetrics listener = new RabbitMqReconnectionMetrics(objectProviderOf(meterRegistry));

        listener.onCreate(mock(Connection.class)); // boot connection
        listener.onCreate(mock(Connection.class)); // steady-state reconnect

        assertThat(meterRegistry.get("rabbitmq_reconnect_attempts_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("rabbitmq_topology_setup_total").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aFailedConnectionAttemptCountsAsAReconnectAttemptButNotATopologySuccess() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RabbitMqReconnectionMetrics listener = new RabbitMqReconnectionMetrics(objectProviderOf(meterRegistry));

        listener.onCreate(mock(Connection.class)); // boot connection
        listener.onFailed(new RuntimeException("connection refused"));

        assertThat(meterRegistry.get("rabbitmq_reconnect_attempts_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("rabbitmq_topology_setup_total").tag("outcome", "success").counter().count())
                .isZero();
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> objectProviderOf(
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(meterRegistry);
        return provider;
    }
}
