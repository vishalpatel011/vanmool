package com.vanmool.paymentservice.dtos;

import com.vanmool.paymentservice.models.GatewayName;
import com.vanmool.paymentservice.models.Payment;
import com.vanmool.paymentservice.models.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// paymentUrl is the page where the customer pays; the status changes only when the gateway confirms
public record PaymentResponseDto(UUID id, UUID orderId, BigDecimal amount, String currency, PaymentStatus status,
                                 GatewayName gateway, String paymentUrl, Instant expiresAt, Instant createdAt) {
    public static PaymentResponseDto from(Payment payment) {
        return new PaymentResponseDto(payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getCurrency(),
                payment.getStatus(), payment.getGateway(), payment.getPaymentUrl(), payment.getExpiresAt(),
                payment.getCreatedAt());
    }
}
