package com.vanmool.apigateway.revocation;

// An event that can never be handled, however often it is tried: it goes to the dead-letter topic at once
public class MalformedEventException extends RuntimeException {
    public MalformedEventException(String message) {
        super(message);
    }
}
