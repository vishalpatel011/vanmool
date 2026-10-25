package com.vanmool.paymentservice.events;

import com.vanmool.paymentservice.models.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Published on payment-events when a gateway confirmed the money. order-service confirms the order;
// notification-service sends the receipt
public record PaymentSucceededEvent(UUID eventId, String eventType, Instant occurredAt, UUID paymentId, UUID orderId,
                                    UUID userId, String customerEmail, BigDecimal amount, String currency,
                                    String gateway, String gatewayPaymentId) implements DomainEvent {
    public static final String TYPE = "PaymentSucceeded";

    public static PaymentSucceededEvent of(Payment payment, String gatewayPaymentId, Instant occurredAt) {
        return new PaymentSucceededEvent(UUID.randomUUID(), TYPE, occurredAt, payment.getId(), payment.getOrderId(),
                payment.getUserId(), payment.getCustomerEmail(), payment.getAmount(), payment.getCurrency(),
                payment.getGateway().name(), gatewayPaymentId);
    }
}
