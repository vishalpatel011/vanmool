package com.vanmool.orderservice.events;

import com.vanmool.orderservice.models.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Published on order-events when an order is cancelled. product-service puts the order's stock back (retrying until
// it succeeds); payment-service closes the order's open payment link; notification-service tells the customer.
// reason: CUSTOMER (the customer cancelled) or PAYMENT_TIMEOUT (not paid within the payment window)
public record OrderCancelledEvent(UUID eventId, String eventType, Instant occurredAt, UUID orderId, UUID userId,
                                  String customerEmail, BigDecimal totalAmount, String reason) implements DomainEvent {
    public static final String TYPE = "OrderCancelled";
    public static final String BY_CUSTOMER = "CUSTOMER";
    public static final String PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT";

    public static OrderCancelledEvent of(Order order, String customerEmail, String reason, Instant occurredAt) {
        return new OrderCancelledEvent(UUID.randomUUID(), TYPE, occurredAt, order.getId(), order.getUserId(),
                customerEmail, order.getTotalAmount(), reason);
    }
}
