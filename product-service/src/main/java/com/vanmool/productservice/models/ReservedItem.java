package com.vanmool.productservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

// One product in a reservation, with its name and price at the moment of reserving: the price the order is placed at
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class ReservedItem {
    @Column(nullable = false)
    private UUID productId;
    @Column(nullable = false)
    private String productName;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;
    @Column(nullable = false)
    private int quantity;
}
