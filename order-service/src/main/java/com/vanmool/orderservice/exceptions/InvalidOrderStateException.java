package com.vanmool.orderservice.exceptions;

// The order's status does not allow the change, for example cancelling an order that has been paid
public class InvalidOrderStateException extends RuntimeException {
    public InvalidOrderStateException(String message) {
        super(message);
    }
}
