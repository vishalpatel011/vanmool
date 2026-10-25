package com.vanmool.productservice.dtos;

import com.vanmool.productservice.projections.CategorySummary;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CategoryResponseDto {
    private String name;
    private long productCount;

    public static CategoryResponseDto from(CategorySummary categorySummary){
        CategoryResponseDto categoryResponseDto = new CategoryResponseDto();
        categoryResponseDto.name = categorySummary.getName();
        categoryResponseDto.productCount = categorySummary.getProductCount();
        return categoryResponseDto;
    }
}
