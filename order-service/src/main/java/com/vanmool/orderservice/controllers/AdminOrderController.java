package com.vanmool.orderservice.controllers;

import com.vanmool.orderservice.dtos.OrderResponseDto;
import com.vanmool.orderservice.dtos.ShipOrderRequestDto;
import com.vanmool.orderservice.models.OrderStatus;
import com.vanmool.orderservice.services.FulfilmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// For Vanmool's operations team: the paid orders waiting to be sent, and marking them shipped and delivered.
// Everything under /admin needs the ADMIN role (SecurityConfig). Customers see the same changes in GET /orders/{id}.
@RestController
@RequestMapping("/admin/orders")
@Tag(name = "Order operations", description = "ADMIN: send paid orders and record their delivery")
public class AdminOrderController {
    private final FulfilmentService fulfilmentService;

    public AdminOrderController(FulfilmentService fulfilmentService) {
        this.fulfilmentService = fulfilmentService;
    }

    // ?status=CONFIRMED (the default: paid, to be sent), oldest first, 20 per page
    @Operation(summary = "Orders in one status, oldest first (default CONFIRMED: paid and waiting to be sent)")
    @GetMapping
    public Page<OrderResponseDto> getOrders(@RequestParam(name = "status", defaultValue = "CONFIRMED") OrderStatus status,
                                            @PageableDefault(size = 20) Pageable pageable) {
        return fulfilmentService.getOrders(status, pageable);
    }

    // State changes, so POST to an action, like POST /orders/{id}/cancel
    @Operation(summary = "Ship a paid order, with its carrier and tracking number")
    @PostMapping("/{id}/ship")
    public OrderResponseDto ship(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID orderId,
                                 @Valid @RequestBody ShipOrderRequestDto request) {
        return fulfilmentService.ship(jwt.getSubject(), orderId, request.carrier(), request.trackingNumber());
    }

    @Operation(summary = "Mark a shipped order as delivered")
    @PostMapping("/{id}/deliver")
    public OrderResponseDto deliver(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID orderId) {
        return fulfilmentService.markDelivered(jwt.getSubject(), orderId);
    }
}
