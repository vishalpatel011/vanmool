package com.vanmool.productservice.repositories;

import com.vanmool.productservice.models.StockReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {
    // SELECT ... FOR UPDATE: a second release of the same order waits here until the first has committed, and then
    // sees RELEASED. Without the lock, both could read RESERVED and put the units back twice.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM StockReservation r WHERE r.orderId = :orderId")
    Optional<StockReservation> findForUpdate(@Param("orderId") UUID orderId);
}
