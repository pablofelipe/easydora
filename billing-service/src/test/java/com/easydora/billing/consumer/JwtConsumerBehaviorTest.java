package com.easydora.billing.consumer;

import com.easydora.billing.config.JwtAuthenticationFilter;
import com.easydora.billing.event.JwtEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Mirrors orders-service's JwtCreatedFanoutBehaviorTest: proves the consumer
 * caches the broadcast token without needing a real RabbitMQ connection.
 * billing-service has no Buyer/Seller-style local entity, so unlike
 * orders-service's JwtConsumer there is no second side effect to verify here.
 */
@ExtendWith(MockitoExtension.class)
class JwtConsumerBehaviorTest {

    @Mock
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void cachesTheTokenFromABroadcastJwtEvent() {
        JwtConsumer jwtConsumer = new JwtConsumer(jwtAuthenticationFilter);

        JwtEvent event = new JwtEvent();
        event.setToken("tok-1");
        event.setUserId(1L);
        event.setEmail("buyer@example.com");
        event.setFirstName("Ana");
        event.setLastName("Silva");
        event.setRole("BUYER");

        jwtConsumer.receiveJwtCreated(event);

        verify(jwtAuthenticationFilter).addValidToken(eq("tok-1"), any());
    }

    @Test
    void ignoresAnEventWithNoToken() {
        JwtConsumer jwtConsumer = new JwtConsumer(jwtAuthenticationFilter);

        JwtEvent event = new JwtEvent();
        event.setUserId(1L);
        event.setEmail("buyer@example.com");

        jwtConsumer.receiveJwtCreated(event);

        verify(jwtAuthenticationFilter, org.mockito.Mockito.never()).addValidToken(any(), any());
    }
}
