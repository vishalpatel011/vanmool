package com.vanmool.orderservice.events;

import com.vanmool.orderservice.models.Order;

import java.time.Instant;
import java.util.UUID;

// Published on order-events when an order is handed to the carrier; notification-service tells the customer how to
// follow it. trackingNumber may be null (local delivery without one)
public record OrderShippedEvent(UUID eventId, String eventType, Instant occurredAt, UUID orderId, UUID userId,
                                String customerEmail, String carrier, String trackingNumber) implements DomainEvent {
    public static final String TYPE = "OrderShipped";

    public static OrderShippedEvent of(Order order, String carrier, String trackingNumber, Instant occurredAt) {
        return new OrderShippedEvent(UUID.randomUUID(), TYPE, occurredAt, order.getId(), order.getUserId(),
                order.getCustomerEmail(), carrier, trackingNumber);
    }
}
