package com.vanmool.productservice.dtos;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ProductRequestDto {
    private String name;
    private BigDecimal price;
    private String description;
    private String imageUrl;
    private String category;
}
