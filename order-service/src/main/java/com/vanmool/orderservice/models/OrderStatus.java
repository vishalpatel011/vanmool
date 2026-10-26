package com.vanmool.orderservice.models;

// The order lifecycle:
//
//   PENDING_PAYMENT --(payment succeeds)--> CONFIRMED --(producer ships)--> SHIPPED --(arrives)--> DELIVERED
//          |
//          +--(customer cancels, or payment fails)--> CANCELLED
//
// PENDING_PAYMENT: placed, the stock is reserved, waiting for payment. CONFIRMED is set when payment-service reports
// the payment; SHIPPED and DELIVERED by Vanmool's operations team (ADMIN), who send the orders the producers hand over.
// Cancelling a CONFIRMED order needs a refund, which is not implemented, so it is not allowed.
public enum OrderStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    public boolean canMoveTo(OrderStatus next) {
        return switch (this) {
            case PENDING_PAYMENT -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED -> next == SHIPPED;
            case SHIPPED -> next == DELIVERED;
            case DELIVERED, CANCELLED -> false;
        };
    }
}
