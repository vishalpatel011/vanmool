package com.vanmool.paymentservice.events;

import com.vanmool.paymentservice.models.Payment;
import com.vanmool.paymentservice.models.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Published on payment-events when a payment link ended without money (FAILED or EXPIRED). The order stays
// PENDING_PAYMENT: the customer can start a new payment
public record PaymentFailedEvent(UUID eventId, String eventType, Instant occurredAt, UUID paymentId, UUID orderId,
                                 UUID userId, String customerEmail, BigDecimal amount, String reason) implements DomainEvent {
    public static final String TYPE = "PaymentFailed";

    public static PaymentFailedEvent of(Payment payment, PaymentStatus status, Instant occurredAt) {
        return new PaymentFailedEvent(UUID.randomUUID(), TYPE, occurredAt, payment.getId(), payment.getOrderId(),
                payment.getUserId(), payment.getCustomerEmail(), payment.getAmount(), status.name());
    }
}
