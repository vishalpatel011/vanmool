package com.vanmool.productservice.controllers;

import com.vanmool.productservice.dtos.CategoryResponseDto;
import com.vanmool.productservice.projections.CategorySummary;
import com.vanmool.productservice.services.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
public class CategoryController {
    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/categories")
    public List<CategoryResponseDto> getAllCategories(){
        List<CategoryResponseDto> categoryResponseDtos = new ArrayList<>();
        for(CategorySummary categorySummary : categoryService.getCategories()){
            categoryResponseDtos.add(CategoryResponseDto.from(categorySummary));
        }
        return categoryResponseDtos;
    }
}
