package com.vanmool.paymentservice.exceptions;

public class OrderServiceUnavailableException extends RuntimeException {
    public OrderServiceUnavailableException(Throwable cause) {
        super("The order service is not available right now", cause);
    }
}
