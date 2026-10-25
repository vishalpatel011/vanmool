package com.vanmool.orderservice.exceptions;

// A valid token whose subject is not a user id, such as a token a service gets for itself: it has no orders
public class NotAUserTokenException extends RuntimeException {
    public NotAUserTokenException() {
        super("This token does not belong to a user");
    }
}
