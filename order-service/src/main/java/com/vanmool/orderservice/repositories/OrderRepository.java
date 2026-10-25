package com.vanmool.orderservice.repositories;

import com.vanmool.orderservice.models.Order;
import com.vanmool.orderservice.models.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

// Every read is scoped to one user: an order id alone never finds another customer's order.
public interface OrderRepository extends JpaRepository<Order, UUID> {
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByIdAndUserId(UUID id, UUID userId);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    // No entity graph here: fetching a collection with a join cannot be paged in SQL (Hibernate would load every
    // order and page in memory). The items come from Order's @BatchSize: one more query for the whole page
    Page<Order> findByUserId(UUID userId, Pageable pageable);

    // Moves the order to the next status only if it is still in the expected one, in one UPDATE: if the payment
    // service confirms the order at the moment the customer cancels it, exactly one of the two changes wins.
    // Returns 1 if the order moved, 0 if its status had already changed
    @Transactional
    @Modifying
    @Query("""
            UPDATE Order o SET o.status = :to, o.version = o.version + 1, o.lastModified = CURRENT_TIMESTAMP
            WHERE o.id = :id AND o.status = :from""")
    int moveStatus(@Param("id") UUID id, @Param("from") OrderStatus from, @Param("to") OrderStatus to);
}
