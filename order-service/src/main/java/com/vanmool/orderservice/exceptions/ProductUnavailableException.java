package com.vanmool.orderservice.exceptions;

// A product in the cart is no longer in the catalog
public class ProductUnavailableException extends RuntimeException {
    public ProductUnavailableException(String message) {
        super(message);
    }
}
