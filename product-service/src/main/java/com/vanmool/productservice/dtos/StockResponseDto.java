package com.vanmool.productservice.dtos;

import java.util.UUID;

// How many units of a product can be sold now. Never cached: it changes with every order
public record StockResponseDto(UUID productId, int quantity) {
}
