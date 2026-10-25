package com.vanmool.paymentservice.dtos;

import com.vanmool.paymentservice.models.GatewayName;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// POST /payments: which order to pay, and optionally with which gateway (RAZORPAY for UPI, cards and net banking;
// STRIPE for international cards). The amount is never sent: it comes from order-service
public record StartPaymentRequestDto(@NotNull UUID orderId, GatewayName gateway) {
}
