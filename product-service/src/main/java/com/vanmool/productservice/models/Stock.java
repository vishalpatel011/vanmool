package com.vanmool.productservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// The units of one product that can still be sold (migration V5). Only read as an entity: every change is a single
// UPDATE or INSERT in StockRepository, so that two orders can never both take the last unit.
@Getter
@Setter
@Entity
public class Stock {
    @Id
    private UUID productId;
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false)
    private Instant lastModified;
}
