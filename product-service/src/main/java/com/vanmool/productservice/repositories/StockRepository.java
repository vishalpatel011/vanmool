package com.vanmool.productservice.repositories;

import com.vanmool.productservice.models.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

// Every change to a stock count is one SQL statement that reads and writes the row together. InnoDB locks the row
// for the statement, so two orders taking the last unit at the same moment run one after the other: the first takes
// it, the second finds quantity 0 and changes nothing. Reading the count in Java and then saving "count - n" would
// let both read 1 and both succeed: the product would be oversold.
public interface StockRepository extends JpaRepository<Stock, UUID> {
    // Takes the units only if that many are left. Returns 1 if they were taken, 0 if not (too few, or no stock row)
    @Modifying
    @Query("""
            UPDATE Stock s SET s.quantity = s.quantity - :quantity, s.lastModified = CURRENT_TIMESTAMP
            WHERE s.productId = :productId AND s.quantity >= :quantity""")
    int take(@Param("productId") UUID productId, @Param("quantity") int quantity);

    // Puts units back, for a released reservation
    @Modifying
    @Query("""
            UPDATE Stock s SET s.quantity = s.quantity + :quantity, s.lastModified = CURRENT_TIMESTAMP
            WHERE s.productId = :productId""")
    int putBack(@Param("productId") UUID productId, @Param("quantity") int quantity);

    // Adds units, creating the product's stock row the first time. MySQL's INSERT ... ON DUPLICATE KEY UPDATE has
    // no JPQL equivalent, so this one is native SQL
    @Modifying
    @Query(value = """
            INSERT INTO stock (product_id, quantity, last_modified) VALUES (:productId, :quantity, NOW(6))
            ON DUPLICATE KEY UPDATE quantity = quantity + :quantity, last_modified = NOW(6)""",
            nativeQuery = true)
    int add(@Param("productId") UUID productId, @Param("quantity") int quantity);
}
