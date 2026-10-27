package com.vanmool.apigateway.revocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

// The tokens ended before their expiry (logout, password change or reset), as revoked-token:{jti} keys in Redis. Each
// key lives as long as its token would have, then Redis removes it: after its expiry the token is refused anyway.
// Shared by every gateway instance.
@Component
public class RevokedTokens {
    static final String KEY_PREFIX = "revoked-token:";
    private static final Logger log = LoggerFactory.getLogger(RevokedTokens.class);

    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;

    public RevokedTokens(ReactiveStringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    // A token that has already expired needs no entry
    public Mono<Boolean> remember(String tokenId, Instant expiresAt) {
        Duration left = Duration.between(clock.instant(), expiresAt);
        if (left.isNegative() || left.isZero()) {
            return Mono.just(false);
        }
        return redis.opsForValue().set(KEY_PREFIX + tokenId, "revoked", left);
    }

    // Redis unreachable: the token is let through. It is still checked by the service (signature, issuer, expiry);
    // only an early logout is not seen until Redis is back
    public Mono<Boolean> isRevoked(String tokenId) {
        return redis.hasKey(KEY_PREFIX + tokenId)
                .onErrorResume(e -> {
                    log.warn("Could not check token {} in Redis, letting it through: {}", tokenId, e.getMessage());
                    return Mono.just(false);
                });
    }
}
