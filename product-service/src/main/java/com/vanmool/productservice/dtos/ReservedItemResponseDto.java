package com.vanmool.productservice.dtos;

import com.vanmool.productservice.models.ReservedItem;

import java.math.BigDecimal;
import java.util.UUID;

public record ReservedItemResponseDto(UUID productId, String name, BigDecimal unitPrice, int quantity) {
    public static ReservedItemResponseDto from(ReservedItem item) {
        return new ReservedItemResponseDto(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity());
    }
}
