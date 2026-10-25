package com.vanmool.orderservice.exceptions;

public class ProductServiceUnavailableException extends RuntimeException {
    public ProductServiceUnavailableException(Throwable cause) {
        super("The product catalog is not available right now", cause);
    }
}
