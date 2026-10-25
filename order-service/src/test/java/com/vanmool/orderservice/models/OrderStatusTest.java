package com.vanmool.orderservice.models;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Every move of the order lifecycle, allowed or not (5 x 5 = 25 cases)
class OrderStatusTest {
    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
            "PENDING_PAYMENT, PENDING_PAYMENT, false",
            "PENDING_PAYMENT, CONFIRMED,       true",
            "PENDING_PAYMENT, SHIPPED,         false",
            "PENDING_PAYMENT, DELIVERED,       false",
            "PENDING_PAYMENT, CANCELLED,       true",
            "CONFIRMED,       PENDING_PAYMENT, false",
            "CONFIRMED,       CONFIRMED,       false",
            "CONFIRMED,       SHIPPED,         true",
            "CONFIRMED,       DELIVERED,       false",
            "CONFIRMED,       CANCELLED,       false",
            "SHIPPED,         PENDING_PAYMENT, false",
            "SHIPPED,         CONFIRMED,       false",
            "SHIPPED,         SHIPPED,         false",
            "SHIPPED,         DELIVERED,       true",
            "SHIPPED,         CANCELLED,       false",
            "DELIVERED,       PENDING_PAYMENT, false",
            "DELIVERED,       CONFIRMED,       false",
            "DELIVERED,       SHIPPED,         false",
            "DELIVERED,       DELIVERED,       false",
            "DELIVERED,       CANCELLED,       false",
            "CANCELLED,       PENDING_PAYMENT, false",
            "CANCELLED,       CONFIRMED,       false",
            "CANCELLED,       SHIPPED,         false",
            "CANCELLED,       DELIVERED,       false",
            "CANCELLED,       CANCELLED,       false",
    })
    void canMoveTo(OrderStatus from, OrderStatus to, boolean allowed) {
        assertEquals(allowed, from.canMoveTo(to));
    }
}
