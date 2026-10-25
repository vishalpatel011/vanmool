package com.vanmool.orderservice.models;

// The order lifecycle:
//
//   PENDING_PAYMENT --(payment succeeds)--> CONFIRMED --(producer ships)--> SHIPPED --(arrives)--> DELIVERED
//          |
//          +--(customer cancels, or payment fails)--> CANCELLED
//
// PENDING_PAYMENT: placed, the stock is reserved, waiting for payment. CONFIRMED will be set by the payment service
// (Days 220-221); SHIPPED and DELIVERED by producers. Cancelling a CONFIRMED order needs a refund, which comes with the
// payment service, so it is not allowed yet.
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
