package com.vanmool.productservice.services;

import com.vanmool.productservice.projections.CategorySummary;
import com.vanmool.productservice.repositories.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryService {
    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public List<CategorySummary> getCategories() {
        return categoryRepository.findCategorySummaries();
    }
}
