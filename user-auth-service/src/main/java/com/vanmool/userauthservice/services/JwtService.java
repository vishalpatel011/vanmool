package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.exceptions.InvalidTokenException;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

// Creates and checks the JWTs handed out at login. A JWT is header.payload.signature, each part Base64URL-encoded:
// the header names the algorithm (HS256), the payload holds the claims, and the signature is an HMAC-SHA256 of the
// first two parts with the secret key, so any change to the header or claims makes the signature wrong.
@Service
public class JwtService {
    static final String ISSUER = "vanmool-user-auth-service";

    private final SecretKey signingKey;
    private final Clock clock;

    public JwtService(SecretKey signingKey, Clock clock) {
        this.signingKey = signingKey;
        this.clock = clock;
    }

    // Claims: iss (who issued it), sub (the user's id), jti (the token's id, the row in the token table that logout
    // revokes), iat and exp (issued and expiry times), plus the email and roles. Nothing secret goes in: the payload
    // is only encoded, and anyone holding the token can read it.
    public String issue(User user, UUID tokenId, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .id(tokenId.toString())
                .claim("email", user.getEmail())
                .claim("roles", user.getRoles().stream().sorted().map(Role::name).toList())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    // Checks the signature, the issuer and the expiry, and returns the claims. Anything wrong (a changed payload,
    // another key, an unsigned "alg: none" token, an expired or malformed token) ends in InvalidTokenException.
    public Claims verify(String jwt) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException();
        }
    }
}
