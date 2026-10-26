package com.vanmool.productservice.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

// The limits match the product table: VARCHAR(2000) description and story, VARCHAR(255) other text, DECIMAL(10,2) price.
// images and specifications are optional lists; left out, the product has none
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

    // In display order; the first is the main image
    @Size(max = 10)
    private List<@NotNull @Valid ProductImageDto> images;

    // In display order; each name at most once
    @Size(max = 20)
    private List<@NotNull @Valid ProductSpecificationDto> specifications;

    @Size(max = 255)
    private String category;

    @Size(max = 255)
    private String originRegion;

    @Size(max = 255)
    private String materials;

    @Size(max = 2000)
    private String story;
}
