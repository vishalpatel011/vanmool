package com.vanmool.orderservice.dtos;

import com.vanmool.orderservice.models.OrderItem;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponseDto(UUID productId, String name, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
    public static OrderItemResponseDto from(OrderItem item) {
        return new OrderItemResponseDto(item.getProductId(), item.getProductName(), item.getUnitPrice(),
                item.getQuantity(), item.lineTotal());
    }
}
