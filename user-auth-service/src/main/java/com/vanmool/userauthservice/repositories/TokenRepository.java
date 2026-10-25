package com.vanmool.userauthservice.repositories;

import com.vanmool.userauthservice.models.Token;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TokenRepository extends JpaRepository<Token, UUID> {
    // A valid token is answered with its user and roles, so they are fetched in the same query (open-in-view is off)
    @EntityGraph(attributePaths = {"user", "user.roles"})
    Optional<Token> findWithUserById(UUID id);

    // One "select ... limit 1" for every request with a bearer token on a protected endpoint
    boolean existsByIdAndRevokedTrue(UUID id);
}
