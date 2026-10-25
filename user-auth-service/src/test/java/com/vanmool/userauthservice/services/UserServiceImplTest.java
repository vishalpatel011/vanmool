package com.vanmool.userauthservice.services;

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.vanmool.userauthservice.exceptions.EmailAlreadyRegisteredException;
import com.vanmool.userauthservice.exceptions.InvalidCredentialsException;
import com.vanmool.userauthservice.exceptions.InvalidTokenException;
import com.vanmool.userauthservice.exceptions.UserNotFoundException;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.Token;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.repositories.TokenRepository;
import com.vanmool.userauthservice.repositories.UserRepository;
import com.vanmool.userauthservice.security.models.CustomUserDetails;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Plain unit test: repositories, the password encoder and Spring Security's AuthenticationManager are Mockito mocks
// (no database, no slow BCrypt). JwtService is real, with a test RSA key: signing and checking JWTs is part of the
// behaviour under test. The clock is fixed, so token expiry can be checked to the exact second.
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final Duration TOKEN_VALIDITY = Duration.ofHours(10);
    private static final UUID ASHA_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");
    private static final UUID TOKEN_ID = UUID.fromString("0f6e2b8a-77d4-4e8a-b1f3-3c2d9a5e4b71");

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    private JwtService jwtService;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = new JwtService(new RSAKeyGenerator(2048).keyID("test-key").generate(), "http://localhost:8081",
                Clock.fixed(NOW, ZoneOffset.UTC));
        userService = new UserServiceImpl(userRepository, tokenRepository, passwordEncoder, authenticationManager,
                jwtService, Clock.fixed(NOW, ZoneOffset.UTC), TOKEN_VALIDITY);
    }

    private static User asha() {
        User user = new User();
        user.setId(ASHA_ID);
        user.setName("Asha Gamit");
        user.setEmail("asha@example.com");
        user.setPasswordHash("$2a$12$ashasHash");
        user.getRoles().add(Role.CUSTOMER);
        return user;
    }

    private static Token token(User user, boolean revoked) {
        Token token = new Token();
        token.setUser(user);
        token.setExpiresAt(NOW.plus(TOKEN_VALIDITY));
        token.setRevoked(revoked);
        return token;
    }

    // What the AuthenticationManager returns after checking the password: the user as Spring Security sees it
    private static Authentication authenticated(User user) {
        CustomUserDetails details = new CustomUserDetails(user);
        return UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());
    }

    // save() gives the token its generated id, as Hibernate does on insert
    private void saveAssignsTokenIds() {
        when(tokenRepository.save(any(Token.class))).thenAnswer(invocation -> {
            Token token = invocation.getArgument(0);
            token.setId(UUID.randomUUID());
            return token;
        });
    }

    private String jwtFor(UUID tokenId) {
        return jwtService.issue(ASHA_ID, "asha@example.com", Set.of(Role.CUSTOMER), tokenId, NOW, NOW.plus(TOKEN_VALIDITY));
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
        void correctPassword_returnsASignedJwtWhoseIdIsTheSavedTokenRow() {
            // Arrange
            User asha = asha();
            when(authenticationManager.authenticate(any())).thenReturn(authenticated(asha));
            when(userRepository.getReferenceById(ASHA_ID)).thenReturn(asha);
            saveAssignsTokenIds();

            // Act
            IssuedToken issued = userService.login(" ASHA@example.com", "correct horse battery");

            // Assert
            ArgumentCaptor<Token> saved = ArgumentCaptor.forClass(Token.class);
            verify(tokenRepository).save(saved.capture());
            Claims claims = jwtService.verify(issued.value());
            assertAll(
                    () -> assertEquals(saved.getValue().getId().toString(), claims.getId()),
                    () -> assertEquals(ASHA_ID.toString(), claims.getSubject()),
                    () -> assertEquals(List.of("CUSTOMER"), claims.get("roles")),
                    () -> assertSame(asha, saved.getValue().getUser()),
                    () -> assertEquals(NOW.plus(TOKEN_VALIDITY), saved.getValue().getExpiresAt()),
                    () -> assertEquals(NOW.plus(TOKEN_VALIDITY), issued.expiresAt()),
                    () -> assertEquals(NOW.plus(TOKEN_VALIDITY), claims.getExpiration().toInstant())
            );
        }

        @Test
        void passesTheNormalizedEmailAndThePasswordToSpringSecurity() {
            // Arrange
            when(authenticationManager.authenticate(any())).thenReturn(authenticated(asha()));
            when(userRepository.getReferenceById(ASHA_ID)).thenReturn(asha());
            saveAssignsTokenIds();

            // Act
            userService.login(" ASHA@example.com ", "correct horse battery");

            // Assert
            verify(authenticationManager).authenticate(argThat(authentication ->
                    authentication.getName().equals("asha@example.com")
                            && authentication.getCredentials().equals("correct horse battery")
                            && !authentication.isAuthenticated()));
        }

        @Test
        void twoLogins_getDifferentTokens() {
            // Arrange
            when(authenticationManager.authenticate(any())).thenReturn(authenticated(asha()));
            when(userRepository.getReferenceById(ASHA_ID)).thenReturn(asha());
            saveAssignsTokenIds();

            // Act
            String first = userService.login("asha@example.com", "correct horse battery").value();
            String second = userService.login("asha@example.com", "correct horse battery").value();

            // Assert: each login is its own token row, so each JWT has its own jti
            assertNotEquals(first, second);
        }

        @Test
        void rejectedBySpringSecurity_throwsInvalidCredentialsAndIssuesNoToken() {
            // Arrange: wrong password and unknown email both arrive as BadCredentialsException
            when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

            // Act & Assert
            assertThrows(InvalidCredentialsException.class, () -> userService.login("asha@example.com", "wrong password"));
            verifyNoInteractions(tokenRepository);
        }
    }

    @Nested
    class Logout {
        @Test
        void validJwt_revokesItsTokenRow() {
            // Arrange
            Token active = token(asha(), false);
            when(tokenRepository.findById(TOKEN_ID)).thenReturn(Optional.of(active));

            // Act
            userService.logout(jwtFor(TOKEN_ID));

            // Assert
            assertTrue(active.isRevoked());
            verify(tokenRepository).save(active);
        }

        @Test
        void invalidJwt_changesNothingAndDoesNotFail() {
            // Act
            userService.logout("not-a-jwt");

            // Assert
            verifyNoInteractions(tokenRepository);
        }
    }

    @Nested
    class ValidateToken {
        @Test
        void validJwtWithAnActiveTokenRow_returnsItsUser() {
            // Arrange
            User asha = asha();
            when(tokenRepository.findWithUserById(TOKEN_ID)).thenReturn(Optional.of(token(asha, false)));

            // Act & Assert
            assertSame(asha, userService.validateToken(jwtFor(TOKEN_ID)));
        }

        @Test
        void tokenEndedByLogout_isRejectedAlthoughItsSignatureIsStillGood() {
            // Arrange
            when(tokenRepository.findWithUserById(TOKEN_ID)).thenReturn(Optional.of(token(asha(), true)));

            // Act & Assert
            assertThrows(InvalidTokenException.class, () -> userService.validateToken(jwtFor(TOKEN_ID)));
        }

        @Test
        void validJwtWithoutATokenRow_isRejected() {
            // Arrange
            when(tokenRepository.findWithUserById(TOKEN_ID)).thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(InvalidTokenException.class, () -> userService.validateToken(jwtFor(TOKEN_ID)));
        }

        @Test
        void expiredJwt_isRejectedWithoutTouchingTheDatabase() {
            // Arrange
            String expired = jwtService.issue(ASHA_ID, "asha@example.com", Set.of(Role.CUSTOMER), TOKEN_ID,
                    NOW.minus(TOKEN_VALIDITY), NOW.minusSeconds(1));

            // Act & Assert
            assertThrows(InvalidTokenException.class, () -> userService.validateToken(expired));
            verifyNoInteractions(tokenRepository);
        }

        @Test
        void malformedJwt_isRejectedWithoutTouchingTheDatabase() {
            // Act & Assert
            assertThrows(InvalidTokenException.class, () -> userService.validateToken("made-up"));
            verifyNoInteractions(tokenRepository);
        }
    }

    @Nested
    class GetUserAndGrantRole {
        @Test
        void getUser_returnsTheUserWithTheirRoles() {
            // Arrange
            User asha = asha();
            when(userRepository.findWithRolesById(ASHA_ID)).thenReturn(Optional.of(asha));

            // Act & Assert
            assertSame(asha, userService.getUser(ASHA_ID));
        }

        @Test
        void getUser_unknownId_throwsUserNotFound() {
            // Arrange
            when(userRepository.findWithRolesById(ASHA_ID)).thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(UserNotFoundException.class, () -> userService.getUser(ASHA_ID));
        }

        @Test
        void grantRole_addsTheRoleAndKeepsTheOthers() {
            // Arrange
            when(userRepository.findWithRolesById(ASHA_ID)).thenReturn(Optional.of(asha()));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            User result = userService.grantRole(ASHA_ID, Role.SELLER);

            // Assert
            assertEquals(Set.of(Role.CUSTOMER, Role.SELLER), result.getRoles());
        }

        @Test
        void grantRole_thatTheUserAlreadyHas_changesNothing() {
            // Arrange
            when(userRepository.findWithRolesById(ASHA_ID)).thenReturn(Optional.of(asha()));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act: PUT is idempotent, so repeating the request is safe
            User result = userService.grantRole(ASHA_ID, Role.CUSTOMER);

            // Assert
            assertEquals(Set.of(Role.CUSTOMER), result.getRoles());
        }

        @Test
        void grantRole_unknownUser_throwsAndSavesNothing() {
            // Arrange
            when(userRepository.findWithRolesById(ASHA_ID)).thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(UserNotFoundException.class, () -> userService.grantRole(ASHA_ID, Role.SELLER));
            verify(userRepository, never()).save(any());
        }
    }
}
