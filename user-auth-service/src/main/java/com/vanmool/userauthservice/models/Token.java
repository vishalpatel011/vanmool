package com.vanmool.userauthservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// A token handed out at login. One user can hold many (one per device or session): User 1 : M Token
@Getter
@Setter
@Entity
public class Token extends Base {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    // SHA-256 of the token the client holds, in hex. The token itself is never stored, so a copy of this table
    // cannot be used to log in, just as the users table holds password hashes, not passwords
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(nullable = false)
    private Instant expiresAt;
    // Set by logout: a revoked token is refused even before it expires
    private boolean revoked;
}
