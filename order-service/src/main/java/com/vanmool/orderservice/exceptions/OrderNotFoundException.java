package com.vanmool.orderservice.exceptions;

import java.util.UUID;

// Also used for another customer's order: the API does not reveal that an order with this id exists
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(UUID orderId) {
        super("Order with id " + orderId + " not found");
    }
}
