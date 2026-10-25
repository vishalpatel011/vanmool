package com.vanmool.productservice.services;

import com.vanmool.productservice.models.Category;
import com.vanmool.productservice.projections.CategorySummary;
import com.vanmool.productservice.repositories.CategoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CategoryService {
    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public List<CategorySummary> getCategories() {
        return categoryRepository.findCategorySummaries();
    }

    // Runs outside the caller's transaction (NOT_SUPPORTED suspends it), so each repository call below commits on its own.
    // That is what makes the duplicate-name recovery work: the insert fails right away instead of when the caller commits,
    // and the second lookup is a new transaction that can see the category the other request just committed.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Category findOrCreateCategory(String categoryName) {
        if(categoryName == null || categoryName.isBlank()) {
            return null;
        }

        Optional<Category> categoryOptional = categoryRepository.findByName(categoryName);
        if(categoryOptional.isPresent()){
            return categoryOptional.get();
        }

        Category newCategory = new Category();
        newCategory.setName(categoryName);
        try {
            return categoryRepository.save(newCategory);
        } catch (DataIntegrityViolationException e) {
            // Another request created this category after our lookup; the unique name constraint stopped a duplicate
            return categoryRepository.findByName(categoryName).orElseThrow();
        }
    }
}
