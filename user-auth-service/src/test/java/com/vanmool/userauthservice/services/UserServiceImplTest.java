package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.exceptions.EmailAlreadyRegisteredException;
import com.vanmool.userauthservice.exceptions.InvalidCredentialsException;
import com.vanmool.userauthservice.exceptions.InvalidTokenException;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.Token;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.repositories.TokenRepository;
import com.vanmool.userauthservice.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Plain unit test: repositories and the password encoder are Mockito mocks (no database, no slow BCrypt), and the
// clock is fixed, so token expiry can be checked to the exact instant
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final Duration TOKEN_VALIDITY = Duration.ofHours(10);

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userRepository, tokenRepository, passwordEncoder,
                Clock.fixed(NOW, ZoneOffset.UTC), TOKEN_VALIDITY);
    }

    private static User asha() {
        User user = new User();
        user.setName("Asha Gamit");
        user.setEmail("asha@example.com");
        user.setPasswordHash("$2a$12$ashasHash");
        user.getRoles().add(Role.CUSTOMER);
        return user;
    }

    private static Token token(User user, Instant expiresAt, boolean revoked) {
        Token token = new Token();
        token.setUser(user);
        token.setExpiresAt(expiresAt);
        token.setRevoked(revoked);
        return token;
    }

    // The service stores SHA-256 hashes of tokens; the test computes the same hash with the JDK to look them up
    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Nested
    class Signup {
        @Test
        void storesTheNormalizedEmailTheHashNotThePasswordAndTheCustomerRole() {
            // Arrange
            when(userRepository.existsByEmail("asha@example.com")).thenReturn(false);
            when(passwordEncoder.encode("correct horse battery")).thenReturn("$2a$12$hashed");
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            User result = userService.signup("  Asha Gamit ", "  Asha@Example.COM ", "correct horse battery");

            // Assert: the service builds the User itself, so a captor catches what was saved
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertSame(saved.getValue(), result);
            assertAll(
                    () -> assertEquals("Asha Gamit", result.getName()),
                    () -> assertEquals("asha@example.com", result.getEmail()),
                    () -> assertEquals("$2a$12$hashed", result.getPasswordHash()),
                    () -> assertEquals(Set.of(Role.CUSTOMER), result.getRoles())
            );
        }

        @Test
        void whenEmailIsTaken_inAnyLetterCase_throwsAndSavesNothing() {
            // Arrange
            when(userRepository.existsByEmail("asha@example.com")).thenReturn(true);

            // Act & Assert
            assertThrows(EmailAlreadyRegisteredException.class,
                    () -> userService.signup("Asha", "ASHA@example.com", "correct horse battery"));
            verify(userRepository, never()).save(any());
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        void whenAnotherSignupWithTheSameEmailWinsTheRace_throwsEmailAlreadyRegistered() {
            // Arrange: the check passed, but a concurrent request inserted the email first and the unique key stopped ours
            when(userRepository.existsByEmail("asha@example.com")).thenReturn(false);
            when(passwordEncoder.encode(any())).thenReturn("$2a$12$hashed");
            when(userRepository.save(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate entry 'asha@example.com' for key 'uk_users_email'"));

            // Act & Assert
            assertThrows(EmailAlreadyRegisteredException.class,
                    () -> userService.signup("Asha", "asha@example.com", "correct horse battery"));
        }
    }

    @Nested
    class Login {
        @Test
        void correctPassword_returnsATokenAndStoresOnlyItsHash() throws Exception {
            // Arrange
            User asha = asha();
            when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(asha));
            when(passwordEncoder.matches("correct horse battery", "$2a$12$ashasHash")).thenReturn(true);

            // Act: the email is matched in any letter case, like at signup
            IssuedToken issued = userService.login(" ASHA@example.com", "correct horse battery");

            // Assert
            ArgumentCaptor<Token> saved = ArgumentCaptor.forClass(Token.class);
            verify(tokenRepository).save(saved.capture());
            assertAll(
                    () -> assertEquals(sha256(issued.value()), saved.getValue().getTokenHash()),
                    () -> assertNotEquals(issued.value(), saved.getValue().getTokenHash()),
                    () -> assertSame(asha, saved.getValue().getUser()),
                    () -> assertEquals(NOW.plus(TOKEN_VALIDITY), saved.getValue().getExpiresAt()),
                    () -> assertEquals(NOW.plus(TOKEN_VALIDITY), issued.expiresAt()),
                    // 32 random bytes in URL-safe Base64 without padding
                    () -> assertEquals(43, issued.value().length())
            );
        }

        @Test
        void twoLogins_getDifferentTokens() {
            // Arrange
            when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(asha()));
            when(passwordEncoder.matches("correct horse battery", "$2a$12$ashasHash")).thenReturn(true);

            // Act
            String first = userService.login("asha@example.com", "correct horse battery").value();
            String second = userService.login("asha@example.com", "correct horse battery").value();

            // Assert
            assertNotEquals(first, second);
        }

        @Test
        void wrongPassword_throwsInvalidCredentialsAndIssuesNoToken() {
            // Arrange
            when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(asha()));
            when(passwordEncoder.matches("wrong password", "$2a$12$ashasHash")).thenReturn(false);

            // Act & Assert
            assertThrows(InvalidCredentialsException.class, () -> userService.login("asha@example.com", "wrong password"));
            verify(tokenRepository, never()).save(any());
        }

        @Test
        void unknownEmail_throwsTheSameExceptionAfterTheSameBcryptWork() {
            // Arrange
            when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

            // Act & Assert: a BCrypt comparison still runs, so this case is not faster than a wrong password
            assertThrows(InvalidCredentialsException.class, () -> userService.login("nobody@example.com", "any password"));
            verify(passwordEncoder).matches(eq("any password"), anyString());
            verify(tokenRepository, never()).save(any());
        }
    }

    @Nested
    class Logout {
        @Test
        void activeToken_isRevoked() throws Exception {
            // Arrange
            Token active = token(asha(), NOW.plusSeconds(60), false);
            when(tokenRepository.findByTokenHash(sha256("token-value"))).thenReturn(Optional.of(active));

            // Act
            userService.logout("token-value");

            // Assert
            assertTrue(active.isRevoked());
            verify(tokenRepository).save(active);
        }

        @Test
        void unknownToken_changesNothingAndDoesNotFail() throws Exception {
            // Arrange
            when(tokenRepository.findByTokenHash(sha256("unknown"))).thenReturn(Optional.empty());

            // Act
            userService.logout("unknown");

            // Assert
            verify(tokenRepository, never()).save(any());
        }
    }

    @Nested
    class ValidateToken {
        @Test
        void activeToken_returnsItsUser() throws Exception {
            // Arrange
            User asha = asha();
            when(tokenRepository.findByTokenHash(sha256("token-value")))
                    .thenReturn(Optional.of(token(asha, NOW.plusSeconds(1), false)));

            // Act & Assert
            assertSame(asha, userService.validateToken("token-value"));
        }

        @Test
        void tokenExpiringExactlyNow_isRejected() throws Exception {
            // Arrange
            when(tokenRepository.findByTokenHash(sha256("token-value")))
                    .thenReturn(Optional.of(token(asha(), NOW, false)));

            // Act & Assert
            assertThrows(InvalidTokenException.class, () -> userService.validateToken("token-value"));
        }

        @Test
        void revokedToken_isRejectedEvenBeforeItExpires() throws Exception {
            // Arrange
            when(tokenRepository.findByTokenHash(sha256("token-value")))
                    .thenReturn(Optional.of(token(asha(), NOW.plusSeconds(3600), true)));

            // Act & Assert
            assertThrows(InvalidTokenException.class, () -> userService.validateToken("token-value"));
        }

        @Test
        void unknownToken_isRejected() throws Exception {
            // Arrange
            when(tokenRepository.findByTokenHash(sha256("made-up"))).thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(InvalidTokenException.class, () -> userService.validateToken("made-up"));
        }
    }
}
