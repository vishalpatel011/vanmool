package com.vanmool.productservice.dtos;

import com.vanmool.productservice.models.Product;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class ProductResponseDto {
    private UUID id;
    private String name;
    private BigDecimal price;
    private String description;
    private String imageUrl;
    private String category;
    private String originRegion;
    private String materials;
    private String story;

    public static ProductResponseDto from(Product product){
        ProductResponseDto productResponseDto = new ProductResponseDto();
        productResponseDto.id = product.getId();
        productResponseDto.name = product.getName();
        productResponseDto.price = product.getPrice();
        productResponseDto.description = product.getDescription();
        productResponseDto.imageUrl = product.getImageUrl();
        productResponseDto.category = product.getCategory() != null ? product.getCategory().getName() : null;
        productResponseDto.originRegion = product.getOriginRegion();
        productResponseDto.materials = product.getMaterials();
        productResponseDto.story = product.getStory();
        return productResponseDto;
    }
}
