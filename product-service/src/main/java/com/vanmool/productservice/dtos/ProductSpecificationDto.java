package com.vanmool.productservice.dtos;

import com.vanmool.productservice.models.ProductSpecification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductSpecificationDto(@NotBlank @Size(max = 60) String name, @NotBlank @Size(max = 255) String value) {
    public static ProductSpecificationDto from(ProductSpecification specification) {
        return new ProductSpecificationDto(specification.name(), specification.value());
    }
}
