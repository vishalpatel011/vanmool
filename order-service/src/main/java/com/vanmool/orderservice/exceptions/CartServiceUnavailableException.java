package com.vanmool.orderservice.exceptions;

public class CartServiceUnavailableException extends RuntimeException {
    public CartServiceUnavailableException(Throwable cause) {
        super("The cart is not available right now", cause);
    }
}
