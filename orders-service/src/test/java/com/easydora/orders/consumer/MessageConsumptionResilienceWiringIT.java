package com.easydora.orders.consumer;

import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.support.ResilienceProbeSupport;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the message consumption resilience policy (limited retry
 * with exponential backoff, then dead-lettering) against a real RabbitMQ (CI
 * Phase 2 service containers), using a dedicated probe queue/listener
 * (ResilienceProbeSupport) instead of any production consumer - the policy
 * itself lives in the shared rabbitListenerContainerFactory bean
 * (RabbitMQConfig), so exercising it here proves it for every listener in
 * this service without touching production queues or business logic.
 *
 * {@code @DirtiesContext} follows the same precedent as StockOutcomeWiringIT:
 * this class's own listener containers should not linger and compete with
 * sibling *IT classes' consumers for the rest of the JVM.
 */
@SpringBootTest
@DirtiesContext
class MessageConsumptionResilienceWiringIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ResilienceProbeSupport probe;

    @Test
    void processesSuccessfullyOnTheFirstAttempt() throws InterruptedException {
        String id = "ok-" + UUID.randomUUID();

        rabbitTemplate.convertAndSend(ResilienceProbeSupport.PROBE_QUEUE, id);

        assertThat(awaitAtLeast(id, 1)).isEqualTo(1);
        Thread.sleep(500);
        assertThat(probe.attemptsFor(id))
                .withFailMessage("a message that succeeds on the first attempt should never be retried")
                .isEqualTo(1);
    }

    @Test
    void retriesAutomaticallyAfterATransientFailureThenSucceeds() throws InterruptedException {
        String id = "retry-" + UUID.randomUUID();
        probe.failFirstAttempts(id, 2);

        rabbitTemplate.convertAndSend(ResilienceProbeSupport.PROBE_QUEUE, id);

        assertThat(awaitAtLeast(id, 3))
                .withFailMessage("message %s should have been retried until the 3rd attempt succeeded", id)
                .isEqualTo(3);
    }

    @Test
    void stopsAfterReachingTheRetryLimitAndRoutesToTheDeadLetterQueue() throws InterruptedException {
        String id = "poison-" + UUID.randomUUID();
        probe.failFirstAttempts(id, Integer.MAX_VALUE);

        rabbitTemplate.convertAndSend(ResilienceProbeSupport.PROBE_QUEUE, id);

        assertThat(awaitAtLeast(id, 3))
                .withFailMessage("message %s should have been attempted exactly 3 times (max-attempts)", id)
                .isEqualTo(3);

        // No further attempts happen once the retry budget (3) is exhausted.
        Thread.sleep(3000);
        assertThat(probe.attemptsFor(id)).isEqualTo(3);

        String dead = (String) rabbitTemplate.receiveAndConvert(RabbitMQConfig.DLQ, 5000);
        assertThat(dead)
                .withFailMessage("message %s should have been republished to the dead letter queue after exhausting retries", id)
                .isEqualTo(id);
    }

    private int awaitAtLeast(String id, int expected) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            if (probe.attemptsFor(id) >= expected) {
                return probe.attemptsFor(id);
            }
            Thread.sleep(250);
        }
        return probe.attemptsFor(id);
    }
}
