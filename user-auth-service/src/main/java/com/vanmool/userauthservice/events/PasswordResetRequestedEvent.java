package com.vanmool.userauthservice.events;

import java.time.Instant;
import java.util.UUID;

// Published on user-events when a user asks to reset their password; notification-service emails them the link.
// The link holds the token itself, so this event is the one place the token exists outside the user's mailbox: in the
// outbox row and on the topic. That is accepted because the link works once and only for 30 minutes, and anyone who
// can read the topic or the database of this service could do much more already. The database keeps only the hash.
public record PasswordResetRequestedEvent(UUID eventId, String eventType, Instant occurredAt, UUID userId, String name,
                                          String email, String resetLink, Instant expiresAt) implements DomainEvent {
    public static final String TYPE = "PasswordResetRequested";

    public static PasswordResetRequestedEvent of(UUID userId, String name, String email, String resetLink,
                                                 Instant expiresAt, Instant occurredAt) {
        return new PasswordResetRequestedEvent(UUID.randomUUID(), TYPE, occurredAt, userId, name, email, resetLink, expiresAt);
    }
}
