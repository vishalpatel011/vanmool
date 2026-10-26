package com.vanmool.userauthservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// One password reset link. The token in the link is never stored, only its SHA-256 hash: the token is 256 random
// bits, so unlike a password it cannot be guessed from the hash, and a fast hash is enough (no BCrypt needed).
// User 1 : M PasswordResetToken
@Getter
@Setter
@Entity
public class PasswordResetToken extends Base {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(nullable = false)
    private Instant expiresAt;
    // Set when the link was used, or when another link of the same user was: the link no longer works
    private Instant usedAt;

    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }
}
