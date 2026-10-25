package com.vanmool.orderservice.controllers;

import com.vanmool.orderservice.dtos.OrderResponseDto;
import com.vanmool.orderservice.dtos.PlaceOrderRequestDto;
import com.vanmool.orderservice.exceptions.InvalidIdempotencyKeyException;
import com.vanmool.orderservice.exceptions.NotAUserTokenException;
import com.vanmool.orderservice.services.OrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

// The signed-in customer's orders. The user id comes from the token's subject, never from the request, so a customer
// can only see and change their own orders.
@RestController
@RequestMapping("/orders")
public class OrderController {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // Places an order from the customer's cart. An optional Idempotency-Key header (any unique string, such as a UUID
    // the client creates once per checkout) makes the request safe to repeat: the same key returns the same order.
    @PostMapping
    public ResponseEntity<OrderResponseDto> placeOrder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequestDto request,
            UriComponentsBuilder uriComponentsBuilder) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        OrderResponseDto order = orderService.placeOrder(userId(jwt), jwt.getTokenValue(), request.shippingAddress(),
                idempotencyKey);
        URI location = uriComponentsBuilder.path("/orders/{id}").buildAndExpand(order.id()).toUri();
        return ResponseEntity.created(location).body(order);
    }

    // Order history, newest first: ?page=0&size=10 (at most 50 per page)
    @GetMapping
    public Page<OrderResponseDto> getOrders(@AuthenticationPrincipal Jwt jwt, @PageableDefault(size = 10) Pageable pageable) {
        return orderService.getOrders(userId(jwt), pageable);
    }

    @GetMapping("/{id}")
    public OrderResponseDto getOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID orderId) {
        return orderService.getOrder(userId(jwt), orderId);
    }

    // A state change, not a removal: the order stays in the history as CANCELLED, so POST rather than DELETE
    @PostMapping("/{id}/cancel")
    public OrderResponseDto cancelOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID orderId) {
        return orderService.cancelOrder(userId(jwt), orderId);
    }

    // user-auth-service puts the user's id in the token's subject ("sub")
    private static UUID userId(Jwt jwt) {
        String subject = jwt.getSubject();
        try {
            return UUID.fromString(subject == null ? "" : subject);
        } catch (IllegalArgumentException e) {
            throw new NotAUserTokenException();
        }
    }
}
