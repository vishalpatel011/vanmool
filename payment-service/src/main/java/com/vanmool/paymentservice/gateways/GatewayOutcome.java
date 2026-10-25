package com.vanmool.paymentservice.gateways;

// A gateway's answer in this service's terms
public enum GatewayOutcome {
    PENDING,
    PAID,
    FAILED,
    EXPIRED
}
