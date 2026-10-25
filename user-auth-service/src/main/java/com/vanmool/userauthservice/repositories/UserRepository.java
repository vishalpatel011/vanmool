package com.vanmool.userauthservice.repositories;

import com.vanmool.userauthservice.models.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    boolean existsByEmail(String email);

    // Signing in turns the user's roles into authorities and puts them into the JWT, so they are fetched in the same
    // query (roles are LAZY and open-in-view is off: reading them later would throw LazyInitializationException)
    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesById(UUID id);
}
