package com.vanmool.paymentservice.gateways;

import java.time.Instant;
import java.util.UUID;

// What a gateway needs to create a payment link. The amount is in the smallest unit (paise)
public record GatewayPaymentRequest(UUID paymentId, UUID orderId, long amountInMinorUnits, String currency,
                                    String customerEmail, String description, Instant expiresAt) {
}
