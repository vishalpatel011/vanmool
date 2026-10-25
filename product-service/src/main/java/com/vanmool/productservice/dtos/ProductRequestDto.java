package com.vanmool.productservice.dtos;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// The limits match the product table: VARCHAR(2000) description and story, VARCHAR(255) other text, DECIMAL(10,2) price
@Getter
@Setter
public class ProductRequestDto {
    @NotBlank
    @Size(max = 255)
    private String name;

    @NotNull
    @Positive
    @Digits(integer = 8, fraction = 2)
    private BigDecimal price;

    @Size(max = 2000)
    private String description;

    @Size(max = 255)
    private String imageUrl;

    @Size(max = 255)
    private String category;

    @Size(max = 255)
    private String originRegion;

    @Size(max = 255)
    private String materials;

    @Size(max = 2000)
    private String story;
}
