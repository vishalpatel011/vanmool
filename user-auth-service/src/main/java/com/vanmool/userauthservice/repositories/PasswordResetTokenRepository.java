package com.vanmool.userauthservice.repositories;

import com.vanmool.userauthservice.models.PasswordResetToken;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    @EntityGraph(attributePaths = "user")
    Optional<PasswordResetToken> findWithUserByTokenHash(String tokenHash);

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant after);

    // Uses the link, in one conditional UPDATE: of two requests with the same link at the same moment, one wins.
    // 0 if it was used meanwhile or has just expired
    @Modifying
    @Query("""
            UPDATE PasswordResetToken t SET t.usedAt = :now, t.lastModified = :now
            WHERE t.id = :id AND t.usedAt IS NULL AND t.expiresAt > :now""")
    int use(@Param("id") UUID id, @Param("now") Instant now);

    // After a reset, the user's other links stop working too
    @Modifying
    @Query("""
            UPDATE PasswordResetToken t SET t.usedAt = :now, t.lastModified = :now
            WHERE t.user.id = :userId AND t.usedAt IS NULL""")
    int closeOpenLinks(@Param("userId") UUID userId, @Param("now") Instant now);
}
