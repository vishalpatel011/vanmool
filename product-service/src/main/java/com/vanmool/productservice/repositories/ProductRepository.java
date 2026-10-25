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

    // "Category_Name" walks Product.category to Category.name, so Spring Data joins the category table;
    // the underscore makes that path explicit instead of leaving Spring Data to guess where the property splits.
    // The column's MySQL collation is case-insensitive, so "natural foods" matches "Natural Foods".
    List<Product> findAllByCategory_NameAndDeletedFalse(String categoryName);
}
