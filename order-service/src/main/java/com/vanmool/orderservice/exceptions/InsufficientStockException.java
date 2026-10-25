package com.vanmool.orderservice.exceptions;

// product-service has too few units of one or more products; its message names them
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
