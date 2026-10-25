package com.vanmool.orderservice.services;

import java.util.UUID;

// The signed-in customer, from their token: the id (subject), the email (for the order emails) and the token itself,
// passed on to cart-service so it returns this customer's cart
public record Customer(UUID id, String email, String bearerToken) {
}
