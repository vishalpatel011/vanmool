package com.vanmool.cartservice.dtos;

import java.math.BigDecimal;
import java.util.UUID;

// One line of the cart. Name and prices come from product-service when the cart is read, never from the cart itself.
// A product that has been removed from the catalog stays in the cart with available = false and no price, so the
// customer sees what happened; it is not counted in the totals.
public record CartItemResponseDto(UUID productId, String name, String imageUrl, BigDecimal unitPrice, int quantity,
                                  BigDecimal lineTotal, boolean available) {
}
