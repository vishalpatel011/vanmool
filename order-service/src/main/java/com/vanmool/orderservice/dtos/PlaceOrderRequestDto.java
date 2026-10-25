package com.vanmool.orderservice.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

// POST /orders: the products come from the customer's cart, the prices from product-service; the client only says
// where to send the order. It never sends prices or totals, which it could change
public record PlaceOrderRequestDto(@NotNull @Valid ShippingAddressDto shippingAddress) {
}
