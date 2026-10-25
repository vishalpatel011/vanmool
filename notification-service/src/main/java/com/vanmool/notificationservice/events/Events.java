package com.vanmool.notificationservice.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// The parts of other services' events that the emails need. Each service keeps its own copy of an event's shape
// (no shared library). Unknown fields are ignored (@JsonIgnoreProperties), so producers can add fields without
// breaking this service, whatever the ObjectMapper's own settings are.
public final class Events {
    private Events() {
    }

    // user-events, from user-auth-service
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserRegistered(UUID eventId, UUID userId, String name, String email) {
    }

    // order-events, from order-service
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderPlaced(UUID eventId, UUID orderId, String customerEmail, List<Item> items, BigDecimal totalAmount,
                              Address shippingAddress) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderCancelled(UUID eventId, UUID orderId, String customerEmail, BigDecimal totalAmount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String name, BigDecimal unitPrice, int quantity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(String name, String phone, String line1, String line2, String city, String state,
                          String pincode) {
    }
}
