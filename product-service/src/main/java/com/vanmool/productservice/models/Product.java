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
    // What the product is: size, use, care. Up to 2000 characters
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
    // Provenance: where the product comes from ("Dangs, Gujarat"), what it is made of ("Bamboo"),
    // and the story of the people who make it. All optional (migration V3)
    private String originRegion;
    private String materials;
    @Column(length = 2000)
    private String story;
}

