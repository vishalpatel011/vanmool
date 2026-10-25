package com.vanmool.productservice.repositories;

import com.vanmool.productservice.models.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    // Products are soft deleted, so reads skip rows whose deleted flag is set
    Optional<Product> findByIdAndDeletedFalse(UUID id);

    List<Product> findAllByDeletedFalse();
}
