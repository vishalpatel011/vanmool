package com.vanmool.userauthservice.security;

import com.vanmool.userauthservice.repositories.TokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevokedTokenValidatorTest {
    private static final UUID TOKEN_ID = UUID.fromString("0f6e2b8a-77d4-4e8a-b1f3-3c2d9a5e4b71");

    @Mock
    private TokenRepository tokenRepository;

    @InjectMocks
    private RevokedTokenValidator revokedTokenValidator;

    private static Jwt jwtWithId(String jti) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("someone")
                .issuedAt(Instant.parse("2026-10-25T10:00:00Z")).expiresAt(Instant.parse("2026-10-25T20:00:00Z"));
        if(jti != null) {
            builder.jti(jti);
        }
        return builder.build();
    }

    @Test
    void tokenEndedByLogout_fails() {
        // Arrange
        when(tokenRepository.existsByIdAndRevokedTrue(TOKEN_ID)).thenReturn(true);

        // Act
        OAuth2TokenValidatorResult result = revokedTokenValidator.validate(jwtWithId(TOKEN_ID.toString()));

        // Assert
        assertTrue(result.hasErrors());
        assertEquals("invalid_token", result.getErrors().iterator().next().getErrorCode());
    }

    @Test
    void activeToken_passes() {
        // Arrange
        when(tokenRepository.existsByIdAndRevokedTrue(TOKEN_ID)).thenReturn(false);

        // Act & Assert
        assertFalse(revokedTokenValidator.validate(jwtWithId(TOKEN_ID.toString())).hasErrors());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", ""})
    void jtiThatIsNotOneOfOurTokenIds_passesWithoutADatabaseCall(String jti) {
        // Act & Assert: such a token cannot have been revoked through our logout
        assertFalse(revokedTokenValidator.validate(jwtWithId(jti)).hasErrors());
        verifyNoInteractions(tokenRepository);
    }

    @Test
    void tokenWithoutAJti_passesWithoutADatabaseCall() {
        // Act & Assert
        assertFalse(revokedTokenValidator.validate(jwtWithId(null)).hasErrors());
        verifyNoInteractions(tokenRepository);
    }
}
