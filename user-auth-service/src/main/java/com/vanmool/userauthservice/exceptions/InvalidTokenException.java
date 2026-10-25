package com.vanmool.userauthservice.exceptions;

public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException() {
        super("The token is missing, invalid, expired or revoked");
    }
}
