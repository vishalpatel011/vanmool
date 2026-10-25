package com.vanmool.paymentservice.exceptions;

import java.util.UUID;

// Also for another customer's order: order-service answers 404 for both
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(UUID orderId) {
        super("Order with id " + orderId + " not found");
    }
}
