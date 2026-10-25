package com.vanmool.userauthservice.services;

import java.time.Instant;

// What login hands back: the token for the client (shown once, never stored as is) and when it stops working
public record IssuedToken(String value, Instant expiresAt) {
}
