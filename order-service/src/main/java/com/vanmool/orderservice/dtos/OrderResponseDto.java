package com.vanmool.orderservice.dtos;

import com.vanmool.orderservice.models.Order;
import com.vanmool.orderservice.models.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponseDto(UUID id, OrderStatus status, List<OrderItemResponseDto> items, BigDecimal totalAmount,
                               ShippingAddressDto shippingAddress, Instant createdAt) {
    // Reads the items: call it while they are loaded (entity graph) or inside a transaction (batch fetching)
    public static OrderResponseDto from(Order order) {
        return new OrderResponseDto(order.getId(), order.getStatus(),
                order.getItems().stream().map(OrderItemResponseDto::from).toList(),
                order.getTotalAmount(), ShippingAddressDto.from(order.getShippingAddress()), order.getCreatedAt());
    }
}
