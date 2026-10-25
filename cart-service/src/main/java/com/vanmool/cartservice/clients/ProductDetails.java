package com.vanmool.cartservice.clients;

import java.math.BigDecimal;
import java.util.UUID;

// The part of product-service's GET /products/{id} response that a cart needs. Other fields in the JSON are ignored,
// so product-service can add fields without breaking this service.
public record ProductDetails(UUID id, String name, BigDecimal price, String imageUrl) {
}
