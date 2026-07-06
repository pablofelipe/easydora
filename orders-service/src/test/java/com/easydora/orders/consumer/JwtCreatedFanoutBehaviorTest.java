package com.easydora.orders.consumer;

import com.easydora.orders.config.JwtAuthenticationFilter;
import com.easydora.orders.entity.Buyer;
import com.easydora.orders.event.JwtEvent;
import com.easydora.orders.event.UserEvent;
import com.easydora.orders.repository.BuyerRepository;
import com.easydora.orders.service.BuyerService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

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
 * behavior one) — see Etapa 5 report for that trade-off.
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

        jwtConsumer.receiveJwtCreated(event);

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

        userEventsConsumer.handleJwtCreated(event);

        verify(buyerRepository).save(any(Buyer.class));
    }
}
