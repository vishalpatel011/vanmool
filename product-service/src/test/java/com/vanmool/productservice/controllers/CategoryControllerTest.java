package com.vanmool.productservice.controllers;

import com.vanmool.productservice.dtos.CategoryResponseDto;
import com.vanmool.productservice.projections.CategorySummary;
import com.vanmool.productservice.services.CategoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryControllerTest {
    @Mock
    private CategoryService categoryService;

    @InjectMocks
    private CategoryController categoryController;

    @Test
    void getAllCategories_mapsEverySummaryToAResponseInTheSameOrder() {
        // Arrange: a class-based projection is a plain object, so the test can simply create it
        when(categoryService.getCategories()).thenReturn(List.of(
                new CategorySummary("Natural Foods", 3L),
                new CategorySummary("Traditional Crafts", 0L)));

        // Act
        List<CategoryResponseDto> response = categoryController.getAllCategories();

        // Assert
        assertEquals(2, response.size());
        assertEquals("Natural Foods", response.get(0).getName());
        assertEquals(3, response.get(0).getProductCount());
        assertEquals("Traditional Crafts", response.get(1).getName());
        assertEquals(0, response.get(1).getProductCount());
    }

    @Test
    void getAllCategories_whenThereAreNone_returnsAnEmptyList() {
        // Arrange
        when(categoryService.getCategories()).thenReturn(List.of());

        // Act & Assert
        assertTrue(categoryController.getAllCategories().isEmpty());
    }
}
