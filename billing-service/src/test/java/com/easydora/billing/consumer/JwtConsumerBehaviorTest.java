package com.easydora.billing.consumer;

import com.easydora.billing.config.JwtAuthenticationFilter;
import com.easydora.billing.event.JwtEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
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

        jwtConsumer.receiveJwtCreated(event, "corr-1", "msg-1");

        verify(jwtAuthenticationFilter).addValidToken(eq("tok-1"), any());
    }

    @Test
    void computesExpiresAtFromCreatedAtPlusExpiresIn() {
        JwtConsumer jwtConsumer = new JwtConsumer(jwtAuthenticationFilter);

        JwtEvent event = new JwtEvent();
        event.setToken("tok-1");
        event.setUserId(1L);
        event.setEmail("buyer@example.com");
        event.setFirstName("Ana");
        event.setLastName("Silva");
        event.setRole("BUYER");
        event.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        event.setExpiresIn(3600L);

        jwtConsumer.receiveJwtCreated(event, "corr-1", "msg-1");

        ArgumentCaptor<JwtAuthenticationFilter.JwtUserInfo> captor =
                ArgumentCaptor.forClass(JwtAuthenticationFilter.JwtUserInfo.class);
        verify(jwtAuthenticationFilter).addValidToken(eq("tok-1"), captor.capture());
        assertThat(captor.getValue().isExpired()).isFalse();
    }

    @Test
    void aTokenWhoseJwtAlreadyExpiredIsCachedAlreadyExpired() {
        JwtConsumer jwtConsumer = new JwtConsumer(jwtAuthenticationFilter);

        JwtEvent event = new JwtEvent();
        event.setToken("tok-1");
        event.setUserId(1L);
        event.setEmail("buyer@example.com");
        event.setFirstName("Ana");
        event.setLastName("Silva");
        event.setRole("BUYER");
        event.setCreatedAt(LocalDateTime.now().minusHours(2));
        event.setExpiresIn(3600L); // the JWT itself expired an hour ago

        jwtConsumer.receiveJwtCreated(event, "corr-1", "msg-1");

        ArgumentCaptor<JwtAuthenticationFilter.JwtUserInfo> captor =
                ArgumentCaptor.forClass(JwtAuthenticationFilter.JwtUserInfo.class);
        verify(jwtAuthenticationFilter).addValidToken(eq("tok-1"), captor.capture());
        assertThat(captor.getValue().isExpired())
                .as("a broadcast for a JWT whose own expiresIn already elapsed should never be cached as valid")
                .isTrue();
    }

    @Test
    void ignoresAnEventWithNoToken() {
        JwtConsumer jwtConsumer = new JwtConsumer(jwtAuthenticationFilter);

        JwtEvent event = new JwtEvent();
        event.setUserId(1L);
        event.setEmail("buyer@example.com");

        jwtConsumer.receiveJwtCreated(event, "corr-2", "msg-2");

        verify(jwtAuthenticationFilter, org.mockito.Mockito.never()).addValidToken(any(), any());
    }
}
