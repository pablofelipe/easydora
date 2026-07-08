package com.easydora.billing.messaging;

import com.easydora.billing.config.JwtAuthenticationFilter;
import com.easydora.billing.config.RabbitMQConfig;
import com.easydora.billing.event.JwtEvent;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publishes a real JwtEvent onto the real auth.exchange (CI Phase 2 service
 * containers) and asserts JwtConsumer actually caches it in
 * JwtAuthenticationFilter -- the real-infrastructure counterpart to
 * JwtConsumerBehaviorTest's pure-Mockito check. Same shape as
 * OrderCreatedWiringIT (plain @SpringBootTest, no explicit webEnvironment)
 * deliberately: an earlier version of this test used
 * webEnvironment = RANDOM_PORT, which gave it its own, separate Spring
 * context/bean set running alongside the shared one the other *IT classes
 * reuse -- two independent RabbitListenerContainers then competed as
 * rival consumers on the same queue (the exact class of bug ADR-0001
 * documents), so this test's own publish could be delivered to the *other*
 * context's listener instead of this test's. Sharing the same context
 * configuration as its sibling *IT classes keeps there being exactly one
 * listener for this queue for the whole test run.
 */
@SpringBootTest
class BillingJwtCreatedWiringIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void jwtCreatedEventResultsInACachedToken() throws Exception {
        String token = "it-token-" + System.nanoTime();

        JwtEvent event = new JwtEvent();
        event.setToken(token);
        event.setUserId(42L);
        event.setEmail("wiring-it@example.com");
        event.setFirstName("Wiring");
        event.setLastName("Test");
        event.setRole("BUYER");

        rabbitTemplate.convertAndSend(RabbitMQConfig.AUTH_EXCHANGE, RabbitMQConfig.JWT_ROUTING_KEY, event);

        boolean cached = awaitTokenCached(token);

        assertThat(cached)
                .withFailMessage("token %s was never cached after a real jwt.created publish", token)
                .isTrue();
    }

    private boolean awaitTokenCached(String token) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            if (jwtAuthenticationFilter.hasValidToken(token)) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }
}
