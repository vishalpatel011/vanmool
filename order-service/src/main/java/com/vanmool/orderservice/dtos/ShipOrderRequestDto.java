package com.vanmool.orderservice.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// POST /admin/orders/{id}/ship: who carries the order, and its tracking number if the carrier gives one
// (India Post EE123456789IN). A cooperative delivering nearby orders itself has no tracking number
public record ShipOrderRequestDto(
        @NotBlank @Size(max = 100) String carrier,
        @Size(max = 50) @Pattern(regexp = "[A-Za-z0-9-]+", message = "may contain only letters, digits and hyphens")
        String trackingNumber) {
}
