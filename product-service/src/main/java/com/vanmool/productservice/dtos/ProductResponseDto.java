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
        ProductResponseDto productResponseDto = new ProductResponseDto();
        productResponseDto.id = product.getId();
        productResponseDto.name = product.getName();
        productResponseDto.price = product.getPrice();
        productResponseDto.description = product.getDescription();
        productResponseDto.imageUrl = product.getImageUrl();
        productResponseDto.category = product.getCategory() != null ? product.getCategory().getName() : null;
        return productResponseDto;
    }
}
