package com.vanmool.orderservice.events;

import com.vanmool.orderservice.dtos.ShippingAddressDto;
import com.vanmool.orderservice.models.Order;
import com.vanmool.orderservice.models.OrderItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Published on order-events when an order is placed. notification-service sends the order confirmation; the payment
// service (Days 220-221) will start the payment from it. It carries everything they need, so neither has to call
// back into order-service.
public record OrderPlacedEvent(UUID eventId, String eventType, Instant occurredAt, UUID orderId, UUID userId,
                               String customerEmail, List<Item> items, BigDecimal totalAmount,
                               ShippingAddressDto shippingAddress) implements DomainEvent {
    public static final String TYPE = "OrderPlaced";

    public record Item(UUID productId, String name, BigDecimal unitPrice, int quantity) {
        static Item from(OrderItem item) {
            return new Item(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity());
        }
    }

    public static OrderPlacedEvent of(Order order, String customerEmail, Instant occurredAt) {
        return new OrderPlacedEvent(UUID.randomUUID(), TYPE, occurredAt, order.getId(), order.getUserId(), customerEmail,
                order.getItems().stream().map(Item::from).toList(), order.getTotalAmount(),
                ShippingAddressDto.from(order.getShippingAddress()));
    }
}
