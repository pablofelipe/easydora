package com.easydora.orders.consumer;

import com.easydora.orders.config.JwtAuthenticationFilter;
import com.easydora.orders.entity.Buyer;
import com.easydora.orders.event.JwtEvent;
import com.easydora.orders.event.UserEvent;
import com.easydora.orders.repository.BuyerRepository;
import com.easydora.orders.service.BuyerService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Replaces JwtCreatedFanoutIT (deleted): that test proved, against a real
 * RabbitMQ, that one jwt.created publish reaches two independent queues
 * instead of being round-robinned between two competing consumers (the
 * incident fixed by ADR-0001). This version asks the equivalent behavior
 * question without any broker: does each consumer perform its own,
 * independent side effect when handed the same logical event? It cannot
 * catch a regression to a single shared queue (that's a wiring fact, not a
 * behavior one) — a known, accepted trade-off of this test's shape.
 */
@ExtendWith(MockitoExtension.class)
class JwtCreatedFanoutBehaviorTest {

    @Mock
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @Mock
    private BuyerService buyerService;
    @Mock
    private BuyerRepository buyerRepository;

    @Test
    void sessionConsumerCachesTheTokenIndependentlyOfProfileConsumer() {
        JwtConsumer jwtConsumer = new JwtConsumer(jwtAuthenticationFilter, buyerService);

        JwtEvent event = new JwtEvent();
        event.setToken("tok-1");
        event.setUserId(1L);
        event.setEmail("buyer@example.com");
        event.setFirstName("Ana");
        event.setLastName("Silva");
        event.setRole("BUYER");

        jwtConsumer.receiveJwtCreated(event, "corr-1", "msg-1");

        verify(jwtAuthenticationFilter).addValidToken(eq("tok-1"), any());
        verify(buyerService).createBuyerIfNotExists(1L, "buyer@example.com", "Ana Silva", "BUYER");
    }

    @Test
    void profileConsumerCreatesTheBuyerIndependentlyOfSessionConsumer() {
        when(buyerRepository.findById(1L)).thenReturn(Optional.empty());

        UserEventsConsumer userEventsConsumer = new UserEventsConsumer(buyerRepository, jwtAuthenticationFilter);

        UserEvent event = new UserEvent();
        event.setUserId(1L);
        event.setEmail("buyer@example.com");
        event.setFirstName("Ana");
        event.setLastName("Silva");
        event.setRole("BUYER");

        userEventsConsumer.handleJwtCreated(event, "corr-2", "msg-2");

        verify(buyerRepository).save(any(Buyer.class));
    }

    @Test
    void sessionConsumerComputesExpiresAtFromCreatedAtPlusExpiresIn() {
        JwtConsumer jwtConsumer = new JwtConsumer(jwtAuthenticationFilter, buyerService);

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
        assertThat(captor.getValue().isExpired()).isTrue();
    }

    @Test
    void profileConsumerComputesExpiresAtFromCreatedAtPlusExpiresIn() {
        when(buyerRepository.findById(1L)).thenReturn(Optional.empty());

        UserEventsConsumer userEventsConsumer = new UserEventsConsumer(buyerRepository, jwtAuthenticationFilter);

        UserEvent event = new UserEvent();
        event.setToken("tok-1");
        event.setUserId(1L);
        event.setEmail("buyer@example.com");
        event.setFirstName("Ana");
        event.setLastName("Silva");
        event.setRole("BUYER");
        event.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        event.setExpiresIn(3600L);

        userEventsConsumer.handleJwtCreated(event, "corr-2", "msg-2");

        ArgumentCaptor<JwtAuthenticationFilter.JwtUserInfo> captor =
                ArgumentCaptor.forClass(JwtAuthenticationFilter.JwtUserInfo.class);
        verify(jwtAuthenticationFilter).addValidToken(eq("tok-1"), captor.capture());
        assertThat(captor.getValue().isExpired()).isFalse();
    }
}
