package com.vanmool.productservice.exceptions;

// A request whose fields are each valid but that breaks a rule across fields, such as the same product twice
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
