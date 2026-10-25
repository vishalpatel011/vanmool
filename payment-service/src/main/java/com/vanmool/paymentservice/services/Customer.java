package com.vanmool.paymentservice.services;

import java.util.UUID;

// The signed-in customer from their token: id, email (for the gateway's checkout page and the receipt) and the token,
// passed on to order-service
public record Customer(UUID id, String email, String bearerToken) {
}
