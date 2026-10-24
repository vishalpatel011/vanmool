package com.vanmool.productservice.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateFakeStoreProductRequestDto {
    private String name;
    private double price;
    private String description;
    private String imageUrl;
    private String category;
}
