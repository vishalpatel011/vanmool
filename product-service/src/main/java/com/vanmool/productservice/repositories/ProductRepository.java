package com.vanmool.productservice.repositories;

import com.vanmool.productservice.models.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    // Products are soft deleted, so reads skip rows whose deleted flag is set
    Optional<Product> findByIdAndDeletedFalse(long id);

    List<Product> findAllByDeletedFalse();
}
