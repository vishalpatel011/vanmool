package com.vanmool.paymentservice.models;

// CREATED: a payment link exists and can be paid. The others are final:
// PAID (the gateway confirmed the money), FAILED (the gateway refused it, or no link could be created),
// EXPIRED (the link ran out of time unpaid). A payment never leaves a final status.
public enum PaymentStatus {
    CREATED,
    PAID,
    FAILED,
    EXPIRED;

    public boolean isFinal() {
        return this != CREATED;
    }
}
