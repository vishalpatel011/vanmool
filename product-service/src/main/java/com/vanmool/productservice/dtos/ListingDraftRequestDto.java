package com.vanmool.productservice.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// A producer's own words about a product, for example "bamboo basket, made by our women's group in Ahwa, for fruit"
public record ListingDraftRequestDto(@NotBlank @Size(max = 1000) String notes) {
}
