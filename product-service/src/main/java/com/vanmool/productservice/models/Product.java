package com.vanmool.productservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
public class Product extends Base{
    private String description;
    private String imageUrl;
    @ManyToOne
    private Category category;
    // Money is stored exactly: DECIMAL(10,2), never a floating-point type
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
}

