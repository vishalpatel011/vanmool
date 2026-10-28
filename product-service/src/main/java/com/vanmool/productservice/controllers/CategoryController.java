package com.vanmool.productservice.controllers;

import com.vanmool.productservice.dtos.CategoryResponseDto;
import com.vanmool.productservice.projections.CategorySummary;
import com.vanmool.productservice.services.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@Tag(name = "Categories", description = "Product categories")
public class CategoryController {
    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "Every category with its number of products")
    @GetMapping("/categories")
    public List<CategoryResponseDto> getAllCategories(){
        List<CategoryResponseDto> categoryResponseDtos = new ArrayList<>();
        for(CategorySummary categorySummary : categoryService.getCategories()){
            categoryResponseDtos.add(CategoryResponseDto.from(categorySummary));
        }
        return categoryResponseDtos;
    }
}
