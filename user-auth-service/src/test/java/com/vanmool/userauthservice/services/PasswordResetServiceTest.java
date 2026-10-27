package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.events.OutboxWriter;
import com.vanmool.userauthservice.events.PasswordChangedEvent;
import com.vanmool.userauthservice.events.PasswordResetRequestedEvent;
import com.vanmool.userauthservice.exceptions.InvalidResetLinkException;
import com.vanmool.userauthservice.models.PasswordResetToken;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.repositories.PasswordResetTokenRepository;
import com.vanmool.userauthservice.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Unit test of the reset link rules. The repositories, the PasswordEncoder and the outbox are mocks; the clock is fixed,
// so "expired" is exact.
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final UUID ASHA_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");
    private static final String LINK = "http://localhost:8081/auth/password-reset/confirm?token=";
    // The shape of a real token: 32 bytes (here 0 to 31) in Base64url, 43 characters
    private static final String TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetTokenRepository resetTokenRepository;

    @Mock
    private SessionRevoker sessionRevoker;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private OutboxWriter outboxWriter;

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(userRepository, resetTokenRepository, sessionRevoker, passwordEncoder,
                outboxWriter, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(30), LINK);
    }

    private static User asha() {
        User user = new User();
        user.setId(ASHA_ID);
        user.setName("Asha Gamit");
        user.setEmail("asha@example.com");
        user.setPasswordHash("$2a$12$oldHash");
        user.getRoles().add(Role.CUSTOMER);
        return user;
    }

    private static PasswordResetToken link(Instant expiresAt, Instant usedAt) {
        PasswordResetToken link = new PasswordResetToken();
        link.setId(UUID.randomUUID());
        link.setUser(asha());
        link.setTokenHash(PasswordResetService.sha256(TOKEN));
        link.setExpiresAt(expiresAt);
        link.setUsedAt(usedAt);
        return link;
    }

    @Nested
    @DisplayName("requestReset")
    class RequestReset {
        @Test
        void knownEmail_storesOnlyTheTokensHash_andEmailsTheLinkWithTheToken() {
            // Arrange: the email as typed, in another case and with spaces
            when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(asha()));
            ArgumentCaptor<PasswordResetToken> stored = ArgumentCaptor.forClass(PasswordResetToken.class);
            ArgumentCaptor<PasswordResetRequestedEvent> event = ArgumentCaptor.forClass(PasswordResetRequestedEvent.class);

            // Act
            service.requestReset("  Asha@Example.com ");

            // Assert: the token travels only in the link; the row has its SHA-256 hash and a 30-minute expiry
            verify(resetTokenRepository).save(stored.capture());
            verify(outboxWriter).add(eq("user-events"), eq(ASHA_ID.toString()), event.capture());
            String link = event.getValue().resetLink();
            assertTrue(link.startsWith(LINK), link);
            String token = link.substring(LINK.length());
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
            assertEquals(PasswordResetService.sha256(token), stored.getValue().getTokenHash());
            assertNotEquals(token, stored.getValue().getTokenHash());
            assertEquals(NOW.plus(Duration.ofMinutes(30)), stored.getValue().getExpiresAt());
            assertEquals(stored.getValue().getExpiresAt(), event.getValue().expiresAt());
            assertEquals("PasswordResetRequested", event.getValue().eventType());
            assertEquals("asha@example.com", event.getValue().email());
            assertEquals("Asha Gamit", event.getValue().name());
        }

        @Test
        void eachRequest_getsADifferentToken() {
            when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(asha()));
            ArgumentCaptor<PasswordResetToken> stored = ArgumentCaptor.forClass(PasswordResetToken.class);

            service.requestReset("asha@example.com");
            service.requestReset("asha@example.com");

            verify(resetTokenRepository, org.mockito.Mockito.times(2)).save(stored.capture());
            assertNotEquals(stored.getAllValues().get(0).getTokenHash(), stored.getAllValues().get(1).getTokenHash());
        }

        @Test
        void unknownEmail_sendsNothing_andAnswersTheSame() {
            when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

            // Act: no exception, so the controller answers 202 as for an account
            service.requestReset("nobody@example.com");

            verifyNoInteractions(resetTokenRepository, outboxWriter);
        }

        @Test
        void fourthLinkWithinAnHour_isNotSent() {
            // Arrange: three links in the last hour already
            when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(asha()));
            when(resetTokenRepository.countByUserIdAndCreatedAtAfter(ASHA_ID, NOW.minus(Duration.ofHours(1)))).thenReturn(3L);

            // Act
            service.requestReset("asha@example.com");

            // Assert
            verify(resetTokenRepository, never()).save(any());
            verifyNoInteractions(outboxWriter);
        }
    }

    @Nested
    @DisplayName("resetPassword")
    class ResetPassword {
        @Test
        void openLink_setsTheNewHash_usesTheLink_closesTheOthers_endsEverySession_andSaysSo() {
            // Arrange
            PasswordResetToken link = link(NOW.plusSeconds(600), null);
            User managed = asha();
            when(resetTokenRepository.findWithUserByTokenHash(PasswordResetService.sha256(TOKEN))).thenReturn(Optional.of(link));
            when(passwordEncoder.encode("tribal weave pattern")).thenReturn("$2a$12$newHash");
            when(resetTokenRepository.use(link.getId(), NOW)).thenReturn(1);
            when(userRepository.findById(ASHA_ID)).thenReturn(Optional.of(managed));
            ArgumentCaptor<PasswordChangedEvent> event = ArgumentCaptor.forClass(PasswordChangedEvent.class);

            // Act
            service.resetPassword(TOKEN, "tribal weave pattern");

            // Assert: the link is used first (so a second request with it fails), then the rest
            assertEquals("$2a$12$newHash", managed.getPasswordHash());
            InOrder steps = inOrder(passwordEncoder, resetTokenRepository, sessionRevoker, outboxWriter);
            steps.verify(passwordEncoder).encode("tribal weave pattern");
            steps.verify(resetTokenRepository).use(link.getId(), NOW);
            steps.verify(resetTokenRepository).closeOpenLinks(ASHA_ID, NOW);
            steps.verify(sessionRevoker).revokeOtherSessions(ASHA_ID, null);
            steps.verify(outboxWriter).add(eq("user-events"), eq(ASHA_ID.toString()), event.capture());
            assertEquals("PasswordChanged", event.getValue().eventType());
            assertFalse(event.getValue().toString().contains("tribal"));
        }

        @Test
        void unknownToken_isRefused_withoutHashingThePassword() {
            when(resetTokenRepository.findWithUserByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThrows(InvalidResetLinkException.class, () -> service.resetPassword("made-up", "tribal weave pattern"));
            verifyNoInteractions(passwordEncoder, sessionRevoker, outboxWriter);
        }

        @Test
        void expiredLink_isRefused() {
            // Expires exactly now: no longer valid
            when(resetTokenRepository.findWithUserByTokenHash(PasswordResetService.sha256(TOKEN)))
                    .thenReturn(Optional.of(link(NOW, null)));

            assertThrows(InvalidResetLinkException.class, () -> service.resetPassword(TOKEN, "tribal weave pattern"));
            verifyNoInteractions(passwordEncoder, outboxWriter);
        }

        @Test
        void usedLink_isRefused() {
            when(resetTokenRepository.findWithUserByTokenHash(PasswordResetService.sha256(TOKEN)))
                    .thenReturn(Optional.of(link(NOW.plusSeconds(600), NOW.minusSeconds(60))));

            assertThrows(InvalidResetLinkException.class, () -> service.resetPassword(TOKEN, "tribal weave pattern"));
            verifyNoInteractions(passwordEncoder, outboxWriter);
        }

        @Test
        void linkUsedByAnotherRequestAtTheSameMoment_changesNothing() {
            // Arrange: the conditional UPDATE finds the link already used
            PasswordResetToken link = link(NOW.plusSeconds(600), null);
            when(resetTokenRepository.findWithUserByTokenHash(PasswordResetService.sha256(TOKEN))).thenReturn(Optional.of(link));
            when(passwordEncoder.encode("tribal weave pattern")).thenReturn("$2a$12$newHash");
            when(resetTokenRepository.use(link.getId(), NOW)).thenReturn(0);

            // Act & Assert
            assertThrows(InvalidResetLinkException.class, () -> service.resetPassword(TOKEN, "tribal weave pattern"));
            verify(userRepository, never()).findById(any());
            verifyNoInteractions(sessionRevoker, outboxWriter);
        }
    }

    @Test
    void sha256_isTheHexOfTheKnownDigest() {
        // The SHA-256 test vector for "abc" (FIPS 180-2)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PasswordResetService.sha256("abc"));
    }
}
