package com.vanmool.paymentservice.exceptions;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(UUID paymentId) {
        this("Payment with id " + paymentId + " not found");
    }

    public PaymentNotFoundException(String message) {
        super(message);
    }
}
