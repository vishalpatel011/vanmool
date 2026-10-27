package com.vanmool.userauthservice.events;

import java.time.Instant;
import java.util.UUID;

// Published on user-events when a token is ended before its expiry: a logout, or the other sessions of a password
// change, or all sessions of a password reset. The API gateway remembers tokenId (the JWT's "jti") until expiresAt
// and refuses the token; the other services check tokens locally and would otherwise accept it until it expires.
public record TokenRevokedEvent(UUID eventId, String eventType, Instant occurredAt, UUID userId, UUID tokenId,
                                Instant expiresAt) implements DomainEvent {
    public static final String TYPE = "TokenRevoked";

    public static TokenRevokedEvent of(UUID userId, UUID tokenId, Instant expiresAt, Instant occurredAt) {
        return new TokenRevokedEvent(UUID.randomUUID(), TYPE, occurredAt, userId, tokenId, expiresAt);
    }
}
