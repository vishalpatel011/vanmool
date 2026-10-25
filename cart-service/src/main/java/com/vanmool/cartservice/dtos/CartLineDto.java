package com.vanmool.cartservice.dtos;

import java.util.UUID;

// GET /cart/items: what is in the cart, without names or prices. Read from Redis only, so it works even while
// product-service is down. The order service uses it at checkout: it takes its prices from the stock reservation.
public record CartLineDto(UUID productId, int quantity) {
}
