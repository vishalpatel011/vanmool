package com.vanmool.paymentservice.gateways;

// What fetchStatus learned: the outcome, the gateway's payment id once paid, and the amount it holds (minor units)
public record GatewayStatus(GatewayOutcome outcome, String gatewayPaymentId, Long amountInMinorUnits) {
}
