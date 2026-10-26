package com.vanmool.productservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
public class Product extends Base{
    // What the product is: size, use, care. Up to 2000 characters
    @Column(length = 2000)
    private String description;
    // The pictures in display order, the first being the main one, and the specifications (PRD 2.2). Each is a JSON
    // array in the product row (migration V6), read with the row itself: no extra query, and nothing LAZY to load
    // after the Hibernate session has closed
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<ProductImage> images = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<ProductSpecification> specifications = new ArrayList<>();
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

    // The picture to show where there is room for one (a list of products, the cart)
    public String mainImageUrl() {
        return images.isEmpty() ? null : images.get(0).url();
    }
}

