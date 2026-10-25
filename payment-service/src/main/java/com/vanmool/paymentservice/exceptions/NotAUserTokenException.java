package com.vanmool.paymentservice.exceptions;

// A valid token whose subject is not a user id, such as a token a service gets for itself
public class NotAUserTokenException extends RuntimeException {
    public NotAUserTokenException() {
        super("This token does not belong to a user");
    }
}
