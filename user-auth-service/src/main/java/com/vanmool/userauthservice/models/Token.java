package com.vanmool.userauthservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// A token handed out at login; its id is the "jti" claim of the JWT. The JWT itself is not stored: its signature
// proves it is genuine. This row exists so that logout can end a token before it expires.
// One user can hold many tokens (one per device or session): User 1 : M Token
@Getter
@Setter
@Entity
public class Token extends Base {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false)
    private Instant expiresAt;
    // Set by logout: a revoked token is refused even before it expires
    private boolean revoked;
}
