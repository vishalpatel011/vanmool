package com.vanmool.orderservice.repositories;

import com.vanmool.orderservice.models.OrderStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderStatusChangeRepository extends JpaRepository<OrderStatusChange, UUID> {
    // One order's timeline, oldest first
    List<OrderStatusChange> findByOrderIdOrderByChangedAtAsc(UUID orderId);

    // The timelines of a whole page of orders in one query
    List<OrderStatusChange> findByOrderIdInOrderByChangedAtAsc(Collection<UUID> orderIds);
}
