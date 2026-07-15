package com.easydora.products.consumer;

import com.easydora.products.config.JwtAuthenticationFilter;
import com.easydora.products.entity.Seller;
import com.easydora.products.event.UserEvent;
import com.easydora.products.repository.SellerRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavior contract for USER_VERIFIED_QUEUE: auth-service publishes
 * user.verified for every user regardless of role (the event is just a
 * bare userId, see auth-service's UserService -- no role field to filter
 * on, unlike user.registered/jwt.created). A BUYER's verification reaches
 * this queue too, so this consumer must not assume a Seller row always
 * exists -- found as a real dead-lettered message in products.dlq (see
 * ADR-0022) once ADR-0019's retry/DLQ policy stopped masking it.
 */
@ExtendWith(MockitoExtension.class)
class UserEventConsumerBehaviorTest {

    @Mock
    private SellerRepository sellerRepository;
    @Mock
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void activatesTheSellerWhenOneExistsForTheVerifiedUser() {
        Seller seller = new Seller();
        seller.setUserId("42");
        when(sellerRepository.findById("42")).thenReturn(Optional.of(seller));

        UserEventConsumer consumer = new UserEventConsumer(sellerRepository, jwtAuthenticationFilter);
        consumer.handleUserVerified(42L, "corr-1", "msg-1");

        verify(sellerRepository).save(seller);
        org.assertj.core.api.Assertions.assertThat(seller.getActive()).isTrue();
    }

    @Test
    void handleJwtCreatedComputesExpiresAtFromCreatedAtPlusExpiresIn() {
        when(sellerRepository.findById("1")).thenReturn(Optional.empty());
        UserEventConsumer consumer = new UserEventConsumer(sellerRepository, jwtAuthenticationFilter);

        UserEvent event = new UserEvent();
        event.setToken("tok-1");
        event.setUserId(1L);
        event.setEmail("seller@example.com");
        event.setFirstName("Ana");
        event.setLastName("Silva");
        event.setRole("SELLER");
        event.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        event.setExpiresIn(3600L);

        consumer.handleJwtCreated(event, "corr-1", "msg-1");

        ArgumentCaptor<JwtAuthenticationFilter.JwtUserInfo> captor =
                ArgumentCaptor.forClass(JwtAuthenticationFilter.JwtUserInfo.class);
        verify(jwtAuthenticationFilter).addValidToken(eq("tok-1"), captor.capture());
        assertThat(captor.getValue().isExpired()).isFalse();
    }

    @Test
    void handleJwtCreatedCachesAnAlreadyExpiredJwtAsExpired() {
        when(sellerRepository.findById("1")).thenReturn(Optional.empty());
        UserEventConsumer consumer = new UserEventConsumer(sellerRepository, jwtAuthenticationFilter);

        UserEvent event = new UserEvent();
        event.setToken("tok-1");
        event.setUserId(1L);
        event.setEmail("seller@example.com");
        event.setFirstName("Ana");
        event.setLastName("Silva");
        event.setRole("SELLER");
        event.setCreatedAt(LocalDateTime.now().minusHours(2));
        event.setExpiresIn(3600L); // the JWT itself expired an hour ago

        consumer.handleJwtCreated(event, "corr-1", "msg-1");

        ArgumentCaptor<JwtAuthenticationFilter.JwtUserInfo> captor =
                ArgumentCaptor.forClass(JwtAuthenticationFilter.JwtUserInfo.class);
        verify(jwtAuthenticationFilter).addValidToken(eq("tok-1"), captor.capture());
        assertThat(captor.getValue().isExpired()).isTrue();
    }

    @Test
    void ignoresAVerifiedUserThatIsNotASeller() {
        when(sellerRepository.findById("99")).thenReturn(Optional.empty());

        UserEventConsumer consumer = new UserEventConsumer(sellerRepository, jwtAuthenticationFilter);

        assertThatCode(() -> consumer.handleUserVerified(99L, "corr-2", "msg-2")).doesNotThrowAnyException();
        verify(sellerRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
