package com.vanmool.productservice.models;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// The units an order holds (migration V5). Its id is the order's id from order-service, so asking twice for the same
// order finds the first reservation instead of taking the stock a second time.
@Getter
@Setter
@Entity
public class StockReservation {
    @Id
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;
    // The items never change after the reservation is made; LAZY, read only inside StockService's transactions
    @ElementCollection
    @CollectionTable(name = "stock_reservation_item", joinColumns = @JoinColumn(name = "order_id"))
    @OrderBy("productId")
    private List<ReservedItem> items = new ArrayList<>();
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(nullable = false)
    private Instant lastModified;
}
