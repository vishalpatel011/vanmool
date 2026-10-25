package com.vanmool.cartservice.dtos;

import java.math.BigDecimal;
import java.util.List;

// The whole cart with current prices. The totals count only the products that are still available.
public record CartResponseDto(List<CartItemResponseDto> items, int totalQuantity, BigDecimal totalPrice) {
}
