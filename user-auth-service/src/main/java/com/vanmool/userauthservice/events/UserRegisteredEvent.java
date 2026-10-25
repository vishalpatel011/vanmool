package com.vanmool.userauthservice.events;

import java.time.Instant;
import java.util.UUID;

// Published on the user-events topic when an account is created. notification-service sends the welcome email; other
// services could react too (a first-order offer), without user-auth-service knowing about them.
// No password or hash ever goes into an event.
public record UserRegisteredEvent(UUID eventId, String eventType, Instant occurredAt, UUID userId, String name,
                                  String email) implements DomainEvent {
    public static final String TYPE = "UserRegistered";

    public static UserRegisteredEvent of(UUID userId, String name, String email, Instant occurredAt) {
        return new UserRegisteredEvent(UUID.randomUUID(), TYPE, occurredAt, userId, name, email);
    }
}
