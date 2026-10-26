package com.vanmool.userauthservice.events;

import java.time.Instant;
import java.util.UUID;

// Published on user-events when a user changes their password. notification-service tells them by email, so that a
// change they did not make does not go unnoticed. Neither password nor hash goes into the event.
public record PasswordChangedEvent(UUID eventId, String eventType, Instant occurredAt, UUID userId, String name,
                                   String email) implements DomainEvent {
    public static final String TYPE = "PasswordChanged";

    public static PasswordChangedEvent of(UUID userId, String name, String email, Instant occurredAt) {
        return new PasswordChangedEvent(UUID.randomUUID(), TYPE, occurredAt, userId, name, email);
    }
}
