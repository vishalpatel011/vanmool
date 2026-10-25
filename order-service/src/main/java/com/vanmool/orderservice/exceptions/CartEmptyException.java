package com.vanmool.orderservice.exceptions;

public class CartEmptyException extends RuntimeException {
    public CartEmptyException() {
        super("The cart is empty; add products before placing an order");
    }
}
