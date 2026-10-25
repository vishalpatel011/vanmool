package com.vanmool.userauthservice.services;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.vanmool.userauthservice.exceptions.InvalidTokenException;
import com.vanmool.userauthservice.models.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// No mocks needed: JwtService is pure computation with a key and a clock, both fixed here
class JwtServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final String ISSUER = "http://localhost:8081";
    private static final UUID USER_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");
    private static final UUID TOKEN_ID = UUID.fromString("0f6e2b8a-77d4-4e8a-b1f3-3c2d9a5e4b71");

    private final RSAKey signingKey = newRsaKey("test-key");
    private final JwtService jwtService = newJwtService(signingKey);

    private static RSAKey newRsaKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JwtService newJwtService(RSAKey key) {
        try {
            return new JwtService(key, ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String issueForAsha() {
        return jwtService.issue(USER_ID, "asha@example.com", Set.of(Role.SELLER, Role.CUSTOMER), TOKEN_ID, NOW, NOW.plusSeconds(3600));
    }

    private static String decode(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void issuedToken_verifiesAndCarriesTheExpectedClaims() {
        // Act
        Claims claims = jwtService.verify(issueForAsha());

        // Assert
        assertAll(
                () -> assertEquals(ISSUER, claims.getIssuer()),
                () -> assertEquals(USER_ID.toString(), claims.getSubject()),
                () -> assertEquals(TOKEN_ID.toString(), claims.getId()),
                () -> assertEquals("asha@example.com", claims.get("email")),
                () -> assertEquals(List.of("CUSTOMER", "SELLER"), claims.get("roles")),
                () -> assertEquals(Date.from(NOW), claims.getIssuedAt()),
                () -> assertEquals(Date.from(NOW.plusSeconds(3600)), claims.getExpiration())
        );
    }

    @Test
    void issuedToken_isHeaderPayloadSignature_signedWithRs256AndNamingItsKey() {
        // Act
        String[] parts = issueForAsha().split("\\.");

        // Assert: the key id lets a checker pick the right public key from /oauth2/jwks
        assertEquals(3, parts.length);
        assertTrue(decode(parts[0]).contains("\"alg\":\"RS256\""));
        assertTrue(decode(parts[0]).contains("\"kid\":\"test-key\""));
    }

    @Test
    void payload_isReadableByAnyone_soItHoldsNoSecret() {
        // Act
        String payload = decode(issueForAsha().split("\\.")[1]);

        // Assert: Base64URL is an encoding, not encryption
        assertTrue(payload.contains("asha@example.com"));
        assertFalse(payload.contains("password"));
    }

    @Test
    void changedPayload_isRejected() {
        // Arrange: give the token the ADMIN role, keeping the original header and signature
        String[] parts = issueForAsha().split("\\.");
        String forged = parts[0] + "." + encode(decode(parts[1]).replace("\"CUSTOMER\",\"SELLER\"", "\"ADMIN\"")) + "." + parts[2];

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(forged));
    }

    @Test
    void tokenSignedWithAnotherKey_isRejected() {
        // Arrange
        String token = newJwtService(newRsaKey("other-key"))
                .issue(USER_ID, "asha@example.com", Set.of(Role.ADMIN), TOKEN_ID, NOW, NOW.plusSeconds(3600));

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(token));
    }

    @Test
    void hs256TokenSignedWithThePublicKey_isRejected() throws Exception {
        // Arrange: "algorithm confusion". The public key is public, so an attacker could use its bytes as an HMAC
        // secret and sign an HS256 token. A checker that trusted the token's own "alg" header would accept it.
        byte[] publicKeyBytes = signingKey.toRSAPublicKey().getEncoded();
        String forged = Jwts.builder().issuer(ISSUER).subject(USER_ID.toString()).id(TOKEN_ID.toString())
                .claim("roles", List.of("ADMIN")).expiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(publicKeyBytes), Jwts.SIG.HS256).compact();

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(forged));
    }

    @Test
    void expiredToken_isRejected() {
        // Arrange
        String token = jwtService.issue(USER_ID, "asha@example.com", Set.of(Role.CUSTOMER), TOKEN_ID,
                NOW.minusSeconds(7200), NOW.minusSeconds(1));

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(token));
    }

    @Test
    void tokenFromAnotherIssuer_isRejectedEvenWithTheRightKey() throws Exception {
        // Arrange
        String token = Jwts.builder().issuer("https://someone-else.example").subject(USER_ID.toString()).id(TOKEN_ID.toString())
                .expiration(Date.from(NOW.plusSeconds(3600))).signWith(signingKey.toRSAPrivateKey(), Jwts.SIG.RS256).compact();

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(token));
    }

    @Test
    void unsignedAlgNoneToken_isRejected() {
        // Arrange: the classic attack, a token that claims to need no signature
        String token = encode("{\"alg\":\"none\"}") + "."
                + encode("{\"iss\":\"" + ISSUER + "\",\"sub\":\"" + USER_ID + "\",\"roles\":[\"ADMIN\"]}") + ".";

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-jwt", "a.b.c"})
    void malformedToken_isRejected(String token) {
        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> jwtService.verify(token));
    }
}
