package com.vanmool.paymentservice.events;

import java.time.Instant;
import java.util.UUID;

// Something that happened in this service that other services may react to. Every event carries these three
// fields first, then its own data, as one flat JSON object.
public interface DomainEvent {
    UUID eventId();

    String eventType();

    Instant occurredAt();
}
