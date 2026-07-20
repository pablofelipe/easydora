package com.easydora.products.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Observes Spring AMQP's own Automatic Connection Recovery + Topology
 * Recovery -- neither is reimplemented here, this only counts events the
 * framework already fires. Same metric names/shapes as
 * inventory-service's (Go) equivalents (docs/adr/0038's Update, the
 * reconnection observability contract): rabbitmq_reconnect_attempts_total
 * and rabbitmq_topology_setup_total{outcome}.
 *
 * <p>The first {@link #onCreate} call is the boot connection, not a
 * reconnect, so it is deliberately not counted -- everything after that is
 * steady-state reconnection. A successful onCreate after the boot
 * connection implies both a successful reconnect and (via the
 * autoconfigured RabbitAdmin, which redeclares every {@code @Bean}-declared
 * exchange/queue/binding on each new connection) a successful topology
 * redeclaration -- Spring AMQP does not expose a separate hook to observe
 * topology redeclaration failing on its own, so a connection failure
 * ({@link #onFailed}) is counted as a reconnect attempt without a topology
 * outcome, since it never got that far.
 */
public class RabbitMqReconnectionMetrics implements ConnectionListener {

    private final Counter reconnectAttempts;
    private final Counter topologySetupSuccess;
    private final AtomicBoolean bootConnectionSeen = new AtomicBoolean(false);

    public RabbitMqReconnectionMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        this.reconnectAttempts = meterRegistry.counter("rabbitmq_reconnect_attempts_total");
        this.topologySetupSuccess = meterRegistry.counter("rabbitmq_topology_setup_total", "outcome", "success");
    }

    @Override
    public void onCreate(Connection connection) {
        if (bootConnectionSeen.compareAndSet(false, true)) {
            return;
        }
        reconnectAttempts.increment();
        topologySetupSuccess.increment();
    }

    @Override
    public void onFailed(Exception exception) {
        reconnectAttempts.increment();
    }
}
