package com.vanmool.userauthservice.repositories;

import com.vanmool.userauthservice.models.Token;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TokenRepository extends JpaRepository<Token, UUID> {
    // A valid token is answered with its user and roles, so they are fetched in the same query (open-in-view is off)
    @EntityGraph(attributePaths = {"user", "user.roles"})
    Optional<Token> findWithUserById(UUID id);

    // One "select ... limit 1" for every request with a bearer token on a protected endpoint
    boolean existsByIdAndRevokedTrue(UUID id);

    // After a password change: ends every other session of the user in one UPDATE. keep is the token the change was
    // made with (null: end them all, for a token that has no row here)
    @Modifying
    @Query("""
            UPDATE Token t SET t.revoked = true, t.lastModified = :now
            WHERE t.user.id = :userId AND t.revoked = false AND (:keep IS NULL OR t.id <> :keep)""")
    int revokeOtherTokens(@Param("userId") UUID userId, @Param("keep") UUID keep, @Param("now") Instant now);
}
