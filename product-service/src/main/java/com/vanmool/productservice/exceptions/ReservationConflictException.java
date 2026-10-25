package com.vanmool.productservice.exceptions;

// A reservation for this order already exists with other items, or has been released
public class ReservationConflictException extends RuntimeException {
    public ReservationConflictException(String message) {
        super(message);
    }
}
