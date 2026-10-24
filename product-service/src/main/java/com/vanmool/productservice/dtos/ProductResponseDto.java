package com.vanmool.productservice.dtos;

import com.vanmool.productservice.models.Product;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductResponseDto {
    private long id;
    private String name;
    private double price;
    private String description;
    private String imageUrl;
    private String category;

    public static ProductResponseDto from(Product product){
        ProductResponseDto ProductResponseDto = new ProductResponseDto();
        ProductResponseDto.id = product.getId();
        ProductResponseDto.name = product.getName();
        ProductResponseDto.price = product.getPrice();
        ProductResponseDto.description = product.getDescription();
        ProductResponseDto.imageUrl = product.getImageUrl();
        ProductResponseDto.category = product.getCategory() != null ? product.getCategory().getName() : null;
        return ProductResponseDto;
    }
}
