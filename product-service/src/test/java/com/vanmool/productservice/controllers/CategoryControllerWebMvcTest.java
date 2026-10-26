package com.vanmool.productservice.controllers;

import com.vanmool.productservice.projections.CategorySummary;
import com.vanmool.productservice.services.CategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The security rules are left out here (addFilters = false): this test is about the web layer itself, and
// ProductSecurityWebMvcTest checks who may call what
@WebMvcTest(CategoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class CategoryControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    @Test
    void getCategories_returnsEachCategoryWithItsProductCount() throws Exception {
        // Arrange
        when(categoryService.getCategories()).thenReturn(List.of(
                new CategorySummary("Natural Foods", 3L),
                new CategorySummary("Traditional Crafts", 0L)));

        // Act & Assert: STRICT also checks the order, which is alphabetical in the API
        mockMvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        [
                          { "name": "Natural Foods", "productCount": 3 },
                          { "name": "Traditional Crafts", "productCount": 0 }
                        ]""", JsonCompareMode.STRICT));
    }

    @Test
    void getCategories_whenThereAreNone_returnsAnEmptyArray() throws Exception {
        // Arrange
        when(categoryService.getCategories()).thenReturn(List.of());

        // Act & Assert
        mockMvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", JsonCompareMode.STRICT));
    }
}
