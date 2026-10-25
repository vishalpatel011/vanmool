package com.vanmool.paymentservice.exceptions;

import java.util.UUID;

// Only a PENDING_PAYMENT order can be paid: not one already paid, cancelled or shipped
public class OrderNotPayableException extends RuntimeException {
    public OrderNotPayableException(UUID orderId, String status) {
        super("Order " + orderId + " is " + status + " and cannot be paid");
    }
}
