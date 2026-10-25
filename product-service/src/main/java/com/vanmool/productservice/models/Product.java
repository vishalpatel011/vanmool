package com.vanmool.productservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
public class Product extends Base{
    // Long enough for how and where a product is made, not just a one-line summary
    @Column(length = 2000)
    private String description;
    private String imageUrl;
    // LAZY: loading a product does not load its category. Queries that need it say so with
    // @EntityGraph in ProductRepository, which fetches it in the same SELECT (a join).
    @ManyToOne(fetch = FetchType.LAZY)
    private Category category;
    // Money is stored exactly: DECIMAL(10,2), never a floating-point type
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
}

