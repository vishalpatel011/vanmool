package com.vanmool.orderservice.events;

import com.vanmool.orderservice.models.Order;

import java.time.Instant;
import java.util.UUID;

// Published on order-events when the order has reached the customer; notification-service tells them
public record OrderDeliveredEvent(UUID eventId, String eventType, Instant occurredAt, UUID orderId, UUID userId,
                                  String customerEmail) implements DomainEvent {
    public static final String TYPE = "OrderDelivered";

    public static OrderDeliveredEvent of(Order order, Instant occurredAt) {
        return new OrderDeliveredEvent(UUID.randomUUID(), TYPE, occurredAt, order.getId(), order.getUserId(),
                order.getCustomerEmail());
    }
}
