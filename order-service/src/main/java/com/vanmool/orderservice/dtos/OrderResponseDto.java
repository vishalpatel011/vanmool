package com.vanmool.orderservice.dtos;

import com.vanmool.orderservice.models.Order;
import com.vanmool.orderservice.models.OrderStatus;
import com.vanmool.orderservice.models.OrderStatusChange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// shipment is null until the order is sent; statusHistory is the order's timeline, oldest first
public record OrderResponseDto(UUID id, OrderStatus status, List<OrderItemResponseDto> items, BigDecimal totalAmount,
                               ShippingAddressDto shippingAddress, ShipmentDto shipment,
                               List<StatusChangeDto> statusHistory, Instant createdAt) {
    // Reads the items: call it while they are loaded (entity graph) or inside a transaction (batch fetching)
    public static OrderResponseDto from(Order order, List<OrderStatusChange> history) {
        ShipmentDto shipment = order.getCarrier() == null ? null
                : new ShipmentDto(order.getCarrier(), order.getTrackingNumber());
        return new OrderResponseDto(order.getId(), order.getStatus(),
                order.getItems().stream().map(OrderItemResponseDto::from).toList(),
                order.getTotalAmount(), ShippingAddressDto.from(order.getShippingAddress()), shipment,
                history.stream().map(StatusChangeDto::from).toList(), order.getCreatedAt());
    }
}
