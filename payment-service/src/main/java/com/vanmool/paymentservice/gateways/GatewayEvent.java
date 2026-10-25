package com.vanmool.paymentservice.gateways;

import java.util.UUID;

// A verified webhook, translated: the gateway's event id (to ignore a second delivery), which of our payments it is
// about (the reference we gave the gateway), and what happened
public record GatewayEvent(String eventId, String eventType, UUID paymentId, GatewayOutcome outcome,
                           String gatewayPaymentId, Long amountInMinorUnits) {
}
