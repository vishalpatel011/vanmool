package com.vanmool.productservice.exceptions;

import java.util.Collection;
import java.util.UUID;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(Collection<UUID> productIds) {
        super("Not enough stock for products " + productIds);
    }
}
