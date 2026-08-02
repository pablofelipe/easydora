package com.easydora.orders.consumer;

import com.easydora.orders.config.JwtAuthenticationFilter;
import com.easydora.orders.entity.Buyer;
import com.easydora.orders.event.UserEvent;
import com.easydora.orders.repository.BuyerRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * auth-service's registerUser now publishes user.registered through the
 * Outbox (up to a 5s poll delay), where it previously went out
 * synchronously and so always arrived before any other event for the same
 * user. That ordering assumption is no longer safe: a real e2e run
 * (signup -> verify -> login, all within a couple of seconds) showed
 * user.registered arriving *after* login's own jwt.created had already
 * activated the buyer via handleJwtCreated -- and handleUserRegistered
 * unconditionally set active=false, silently deactivating a buyer that
 * was already correctly active. This test reproduces that regression
 * directly, without needing the full timing-dependent chain.
 */
@ExtendWith(MockitoExtension.class)
class UserEventsConsumerTest {

    @Mock
    private BuyerRepository buyerRepository;
    @Mock
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private UserEvent registeredEvent(Long userId) {
        UserEvent event = new UserEvent();
        event.setUserId(userId);
        event.setEmail("buyer@example.com");
        event.setFirstName("Riley");
        event.setLastName("Buyer");
        event.setRole("BUYER");
        return event;
    }

    @Test
    void handleUserRegisteredDoesNotDeactivateABuyerAlreadyActivatedByALaterEvent() {
        Buyer alreadyActiveBuyer = new Buyer();
        alreadyActiveBuyer.setUserId(2L);
        alreadyActiveBuyer.setEmail("buyer@example.com");
        alreadyActiveBuyer.setActive(true);
        alreadyActiveBuyer.setCreatedAt(LocalDateTime.now());
        when(buyerRepository.findById(2L)).thenReturn(Optional.of(alreadyActiveBuyer));

        UserEventsConsumer consumer = new UserEventsConsumer(buyerRepository, jwtAuthenticationFilter);
        consumer.handleUserRegistered(registeredEvent(2L), "corr-1", "msg-1");

        ArgumentCaptor<Buyer> saved = ArgumentCaptor.forClass(Buyer.class);
        verify(buyerRepository).save(saved.capture());
        assertThat(saved.getValue().isActive())
                .withFailMessage("a delayed/redelivered user.registered must not deactivate an already-active buyer")
                .isTrue();
    }

    @Test
    void handleUserRegisteredStillCreatesANewBuyerAsInactive() {
        when(buyerRepository.findById(3L)).thenReturn(Optional.empty());

        UserEventsConsumer consumer = new UserEventsConsumer(buyerRepository, jwtAuthenticationFilter);
        consumer.handleUserRegistered(registeredEvent(3L), "corr-2", "msg-2");

        ArgumentCaptor<Buyer> saved = ArgumentCaptor.forClass(Buyer.class);
        verify(buyerRepository).save(saved.capture());
        assertThat(saved.getValue().isActive())
                .withFailMessage("a genuinely new buyer must still start inactive until email verification/login")
                .isFalse();
    }
}
