package com.vanmool.cartservice.exceptions;

// product-service did not answer in time, could not be reached, or failed
public class ProductServiceUnavailableException extends RuntimeException {
    public ProductServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
