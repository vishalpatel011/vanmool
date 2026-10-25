package com.vanmool.orderservice.events;

import com.vanmool.orderservice.models.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Published on order-events when an order is cancelled. product-service puts the order's stock back (retrying until
// it succeeds); notification-service tells the customer.
public record OrderCancelledEvent(UUID eventId, String eventType, Instant occurredAt, UUID orderId, UUID userId,
                                  String customerEmail, BigDecimal totalAmount) implements DomainEvent {
    public static final String TYPE = "OrderCancelled";

    public static OrderCancelledEvent of(Order order, String customerEmail, Instant occurredAt) {
        return new OrderCancelledEvent(UUID.randomUUID(), TYPE, occurredAt, order.getId(), order.getUserId(),
                customerEmail, order.getTotalAmount());
    }
}
