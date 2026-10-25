package com.vanmool.paymentservice.exceptions;

// The order's link has passed its expiry, but the gateway has not closed it yet
public class PaymentInProgressException extends RuntimeException {
    public PaymentInProgressException(String message) {
        super(message);
    }
}
