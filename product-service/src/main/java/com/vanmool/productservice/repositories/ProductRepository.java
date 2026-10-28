package com.vanmool.productservice.repositories;

import com.vanmool.productservice.models.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Product.category is LAZY. Every product response shows the category name, so these reads fetch it with
// @EntityGraph: one SELECT with a join, instead of one extra SELECT per category (the N+1 problem).
public interface ProductRepository extends JpaRepository<Product, UUID> {
    // Products are soft deleted, so reads skip rows whose deleted flag is set
    @EntityGraph(attributePaths = "category")
    Optional<Product> findByIdAndDeletedFalse(UUID id);

    // Every product for sale, with its category: what the shopping assistant's catalog index is built from
    @EntityGraph(attributePaths = "category")
    List<Product> findAllByDeletedFalse();

    boolean existsByIdAndDeletedFalse(UUID id);

    // The products of a stock reservation: only their names and prices are used, so the category is not fetched
    List<Product> findAllByIdInAndDeletedFalse(Collection<UUID> ids);

    // The catalog query behind GET /products. Every filter is optional: a null parameter switches its condition off.
    // textPattern and originPattern are LIKE patterns ("%word%") built by the service, with "!" escaping a literal
    // %, _ or ! typed by the user. The column collation makes matching case- and accent-insensitive.
    // Pageable adds the LIMIT/OFFSET of the requested page and its ORDER BY; Spring Data also derives the count
    // query that gives the total number of matches.
    @EntityGraph(attributePaths = "category")
    @Query("""
            SELECT p FROM Product p LEFT JOIN p.category c
            WHERE p.deleted = false
              AND (:textPattern IS NULL
                   OR p.name LIKE :textPattern ESCAPE '!'
                   OR p.description LIKE :textPattern ESCAPE '!'
                   OR p.materials LIKE :textPattern ESCAPE '!'
                   OR p.originRegion LIKE :textPattern ESCAPE '!'
                   OR p.story LIKE :textPattern ESCAPE '!')
              AND (:category IS NULL OR c.name = :category)
              AND (:originPattern IS NULL OR p.originRegion LIKE :originPattern ESCAPE '!')
              AND (:minPrice IS NULL OR p.price >= :minPrice)
              AND (:maxPrice IS NULL OR p.price <= :maxPrice)""")
    Page<Product> search(@Param("textPattern") String textPattern,
                         @Param("category") String category,
                         @Param("originPattern") String originPattern,
                         @Param("minPrice") BigDecimal minPrice,
                         @Param("maxPrice") BigDecimal maxPrice,
                         Pageable pageable);
}
