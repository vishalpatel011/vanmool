package com.vanmool.userauthservice.services;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.vanmool.userauthservice.exceptions.InvalidTokenException;
import com.vanmool.userauthservice.models.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.UUID;

// Creates and checks the JWTs handed out by POST /auth/login. A JWT is header.payload.signature, each part
// Base64URL-encoded: the header names the algorithm (RS256) and the key id, the payload holds the claims, and the
// signature is made over the first two parts with the private key, so any change to them makes the signature wrong.
// It is the same key the OAuth2 authorization server uses (JwtConfig), so both kinds of token are checked the same way.
@Service
public class JwtService {
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;
    private final String issuer;
    private final Clock clock;

    public JwtService(RSAKey jwtSigningKey, @Value("${auth.issuer}") String issuer, Clock clock) throws JOSEException {
        this.privateKey = jwtSigningKey.toRSAPrivateKey();
        this.publicKey = jwtSigningKey.toRSAPublicKey();
        this.keyId = jwtSigningKey.getKeyID();
        this.issuer = issuer;
        this.clock = clock;
    }

    // Claims: iss (who issued it), sub (the user's id), jti (the token's id, the row in the token table that logout
    // revokes), iat and exp (issued and expiry times), plus the email and roles. Nothing secret goes in: the payload
    // is only encoded, and anyone holding the token can read it.
    public String issue(UUID userId, String email, Collection<Role> roles, UUID tokenId, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .header().keyId(keyId).and()
                .issuer(issuer)
                .subject(userId.toString())
                .id(tokenId.toString())
                .claim("email", email)
                .claim("roles", roles.stream().sorted().map(Role::name).toList())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    // Checks the signature, the issuer and the expiry, and returns the claims. Anything wrong (a changed payload,
    // another key, an unsigned "alg: none" token, an expired or malformed token) ends in InvalidTokenException.
    public Claims verify(String jwt) {
        try {
            return Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(issuer)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException();
        }
    }
}
