package com.easydora.orders.support;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only probe queue/listener, wired to the same rabbitListenerContainerFactory
 * bean as every production consumer in this service, used to exercise the
 * retry/backoff/DLQ policy in isolation - no production queue or
 * bean is touched, so this cannot race with any other test's listener
 * container on a shared queue.
 */
@Configuration
public class ResilienceProbeSupport {

    public static final String PROBE_QUEUE = "orders.test.resilience.probe.queue";

    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Map<String, Integer> failUntilAttempt = new ConcurrentHashMap<>();

    @Bean
    public Queue resilienceProbeQueue() {
        return new Queue(PROBE_QUEUE, true);
    }

    public void failFirstAttempts(String id, int failCount) {
        failUntilAttempt.put(id, failCount);
    }

    public int attemptsFor(String id) {
        return attempts.getOrDefault(id, new AtomicInteger(0)).get();
    }

    @RabbitListener(queues = PROBE_QUEUE)
    public void onMessage(String id) {
        int attempt = attempts.computeIfAbsent(id, k -> new AtomicInteger(0)).incrementAndGet();
        int failCount = failUntilAttempt.getOrDefault(id, 0);
        if (attempt <= failCount) {
            throw new RuntimeException("Simulated transient failure, attempt " + attempt + " for " + id);
        }
    }
}
