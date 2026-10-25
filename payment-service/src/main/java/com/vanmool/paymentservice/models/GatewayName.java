package com.vanmool.paymentservice.models;

public enum GatewayName {
    RAZORPAY,
    STRIPE,
    // For development and tests only (payment.fake.enabled)
    FAKE
}
