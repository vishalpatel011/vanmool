package com.vanmool.productservice.dtos;

import com.vanmool.productservice.models.ProductImage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// The images are stored elsewhere (a CDN or object storage such as S3); the product holds their addresses
public record ProductImageDto(
        @NotBlank @Size(max = 500) @Pattern(regexp = "\\s*https?://\\S+\\s*", message = "must be an http or https address")
        String url,
        @Size(max = 255) String altText) {
    public static ProductImageDto from(ProductImage image) {
        return new ProductImageDto(image.url(), image.altText());
    }
}
