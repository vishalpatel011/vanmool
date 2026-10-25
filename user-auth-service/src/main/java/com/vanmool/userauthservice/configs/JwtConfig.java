package com.vanmool.userauthservice.configs;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;

@Slf4j
@Configuration
public class JwtConfig {
    // The HS256 signing key, from JWT_SECRET (Base64 of at least 32 random bytes; a shorter key fails at startup).
    // Every instance of the service must use the same key, or one instance rejects another's tokens, and the key must
    // never be in the repository. Without it, a random key is made at startup: fine on a developer's machine only.
    @Bean
    public SecretKey jwtSigningKey(@Value("${auth.jwt.secret:}") String secret) {
        if(secret.isBlank()) {
            log.warn("JWT_SECRET is not set: signing tokens with a random key. Tokens stop working when the service "
                    + "restarts, and instances do not accept each other's tokens. Set JWT_SECRET outside local development.");
            return Jwts.SIG.HS256.key().build();
        }
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }
}
