package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.exceptions.InvalidTokenException;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// No mocks needed: JwtService is pure computation with a key and a clock, both fixed here
class JwtServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final UUID USER_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");
    private static final UUID TOKEN_ID = UUID.fromString("0f6e2b8a-77d4-4e8a-b1f3-3c2d9a5e4b71");

    private final SecretKey key = Jwts.SIG.HS256.key().build();
    private final JwtService jwtService = new JwtService(key, Clock.fixed(NOW, ZoneOffset.UTC));

    private static User asha() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("asha@example.com");
        user.setPasswordHash("$2a$12$ashasHash");
        user.getRoles().add(Role.SELLER);
        user.getRoles().add(Role.CUSTOMER);
        return user;
    }

    private String issueForAsha() {
        return jwtService.issue(asha(), TOKEN_ID, NOW, NOW.plusSeconds(3600));
    }

    private static String decode(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }

    @Test
    void issuedToken_verifiesAndCarriesTheExpectedClaims() {
        // Act
        Claims claims = jwtService.verify(issueForAsha());

        // Assert
        assertAll(
                () -> assertEquals("vanmool-user-auth-service", claims.getIssuer()),
                () -> assertEquals(USER_ID.toString(), claims.getSubject()),
                () -> assertEquals(TOKEN_ID.toString(), claims.getId()),
                () -> assertEquals("asha@example.com", claims.get("email")),
                () -> assertEquals(List.of("CUSTOMER", "SELLER"), claims.get("roles")),
                () -> assertEquals(Date.from(NOW), claims.getIssuedAt()),
                () -> assertEquals(Date.from(NOW.plusSeconds(3600)), claims.getExpiration())
        );
    }

    @Test
    void issuedToken_isHeaderPayloadSignature_signedWithHs256() {
        // Act
        String[] parts = issueForAsha().split("\\.");

        // Assert
        assertEquals(3, parts.length);
        assertTrue(decode(parts[0]).contains("\"alg\":\"HS256\""));
    }

    @Test
    void payload_isReadableByAnyone_soItHoldsNoSecret() {
        // Act
        String payload = decode(issueForAsha().split("\\.")[1]);

        // Assert: Base64URL is an encoding, not encryption
        assertTrue(payload.contains("asha@example.com"));
        assertFalse(payload.contains("ashasHash"));
    }

    @Test
    void changedPayload_isRejected() {
        // Arrange: give the token the ADMIN role, keeping the original header and signature
        String[] parts = issueForAsha().split("\\.");
        String forgedPayload = decode(parts[1]).replace("\"CUSTOMER\",\"SELLER\"", "\"ADMIN\"");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(forged));
    }

    @Test
    void tokenSignedWithAnotherKey_isRejected() {
        // Arrange
        JwtService otherIssuer = new JwtService(Jwts.SIG.HS256.key().build(), Clock.fixed(NOW, ZoneOffset.UTC));
        String token = otherIssuer.issue(asha(), TOKEN_ID, NOW, NOW.plusSeconds(3600));

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(token));
    }

    @Test
    void expiredToken_isRejected() {
        // Arrange
        String token = jwtService.issue(asha(), TOKEN_ID, NOW.minusSeconds(7200), NOW.minusSeconds(1));

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(token));
    }

    @Test
    void tokenFromAnotherIssuer_isRejectedEvenWithTheRightKey() {
        // Arrange
        String token = Jwts.builder().issuer("someone-else").subject(USER_ID.toString()).id(TOKEN_ID.toString())
                .expiration(Date.from(NOW.plusSeconds(3600))).signWith(key).compact();

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(token));
    }

    @Test
    void unsignedAlgNoneToken_isRejected() {
        // Arrange: the classic attack, a token that claims to need no signature
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"vanmool-user-auth-service\",\"sub\":\"" + USER_ID + "\",\"roles\":[\"ADMIN\"]}").getBytes(StandardCharsets.UTF_8));

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(header + "." + payload + "."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-jwt", "a.b.c"})
    void malformedToken_isRejected(String token) {
        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(token));
    }
}
