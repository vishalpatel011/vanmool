package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.events.OutboxWriter;
import com.vanmool.userauthservice.events.TokenRevokedEvent;
import com.vanmool.userauthservice.models.Token;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.repositories.TokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionRevokerTest {
    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final UUID ASHA_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private OutboxWriter outboxWriter;

    private SessionRevoker sessionRevoker;

    @BeforeEach
    void setUp() {
        sessionRevoker = new SessionRevoker(tokenRepository, outboxWriter, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Token session(boolean revoked) {
        User asha = new User();
        asha.setId(ASHA_ID);
        Token token = new Token();
        token.setId(UUID.randomUUID());
        token.setUser(asha);
        token.setExpiresAt(NOW.plus(Duration.ofHours(8)));
        token.setRevoked(revoked);
        return token;
    }

    @Test
    void revoke_endsTheToken_andAnnouncesIt_withItsIdAndExpiry() {
        // Arrange
        Token phone = session(false);
        ArgumentCaptor<TokenRevokedEvent> event = ArgumentCaptor.forClass(TokenRevokedEvent.class);

        // Act
        sessionRevoker.revoke(phone);

        // Assert: keyed by the user, like the other user events
        assertTrue(phone.isRevoked());
        verify(tokenRepository).save(phone);
        verify(outboxWriter).add(eq("user-events"), eq(ASHA_ID.toString()), event.capture());
        assertEquals("TokenRevoked", event.getValue().eventType());
        assertEquals(phone.getId(), event.getValue().tokenId());
        assertEquals(ASHA_ID, event.getValue().userId());
        assertEquals(NOW.plus(Duration.ofHours(8)), event.getValue().expiresAt());
        assertEquals(NOW, event.getValue().occurredAt());
    }

    @Test
    void revoke_ofATokenAlreadyEnded_changesNothing_andIsNotAnnouncedAgain() {
        sessionRevoker.revoke(session(true));

        verifyNoInteractions(tokenRepository, outboxWriter);
    }

    @Test
    void revokeOtherSessions_endsEveryUsableSessionButTheOneToKeep_eachWithItsOwnEvent() {
        // Arrange: three sessions still usable now; the phone made the change
        Token phone = session(false);
        Token laptop = session(false);
        Token tablet = session(false);
        when(tokenRepository.findByUserIdAndRevokedFalseAndExpiresAtAfter(ASHA_ID, NOW)).thenReturn(List.of(phone, laptop, tablet));
        ArgumentCaptor<TokenRevokedEvent> events = ArgumentCaptor.forClass(TokenRevokedEvent.class);

        // Act
        int revoked = sessionRevoker.revokeOtherSessions(ASHA_ID, phone.getId());

        // Assert
        assertEquals(2, revoked);
        assertFalse(phone.isRevoked());
        assertTrue(laptop.isRevoked() && tablet.isRevoked());
        verify(outboxWriter, times(2)).add(eq("user-events"), eq(ASHA_ID.toString()), events.capture());
        assertEquals(List.of(laptop.getId(), tablet.getId()), events.getAllValues().stream().map(TokenRevokedEvent::tokenId).toList());
    }

    @Test
    void revokeOtherSessions_withNothingToKeep_endsThemAll() {
        Token phone = session(false);
        Token laptop = session(false);
        when(tokenRepository.findByUserIdAndRevokedFalseAndExpiresAtAfter(ASHA_ID, NOW)).thenReturn(List.of(phone, laptop));

        assertEquals(2, sessionRevoker.revokeOtherSessions(ASHA_ID, null));
        assertTrue(phone.isRevoked() && laptop.isRevoked());
    }
}
