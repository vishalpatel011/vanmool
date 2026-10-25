package com.vanmool.productservice.repositories;

import com.vanmool.productservice.models.Category;
import com.vanmool.productservice.projections.CategorySummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByName(String categoryName);

    // JPQL is written against entities and fields (Category, c.products), not tables, and Hibernate checks it at startup.
    // The products are counted by the database, so no Product entity is loaded; soft-deleted products are not counted.
    @Query("""
            SELECT new com.vanmool.productservice.projections.CategorySummary(c.name, COUNT(p))
            FROM Category c LEFT JOIN c.products p ON p.deleted = false
            WHERE c.deleted = false
            GROUP BY c.id, c.name
            ORDER BY c.name""")
    List<CategorySummary> findCategorySummaries();
}
