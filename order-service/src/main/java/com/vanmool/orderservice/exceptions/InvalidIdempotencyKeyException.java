package com.vanmool.orderservice.exceptions;

public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key: must be 1 to 100 letters, digits, - or _");
    }
}
