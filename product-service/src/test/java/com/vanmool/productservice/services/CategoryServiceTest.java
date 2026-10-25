package com.vanmool.productservice.services;

import com.vanmool.productservice.models.Category;
import com.vanmool.productservice.repositories.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Plain unit test: no Spring context and no database. The repository is a Mockito mock, injected into the service.
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {
    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryService categoryService;

    private static Category category(String name) {
        Category category = new Category();
        category.setName(name);
        return category;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void findOrCreateCategory_withoutAName_returnsNullAndNeverTouchesTheDatabase(String categoryName) {
        // Act
        Category result = categoryService.findOrCreateCategory(categoryName);

        // Assert
        assertNull(result);
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void findOrCreateCategory_whenCategoryExists_returnsItWithoutCreatingAnother() {
        // Arrange
        Category naturalFoods = category("Natural Foods");
        when(categoryRepository.findByName("Natural Foods")).thenReturn(Optional.of(naturalFoods));

        // Act
        Category result = categoryService.findOrCreateCategory("Natural Foods");

        // Assert
        assertSame(naturalFoods, result);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void findOrCreateCategory_whenCategoryIsNew_savesACategoryWithThatName() {
        // Arrange
        when(categoryRepository.findByName("Tribal Art")).thenReturn(Optional.empty());
        // save() returns the entity it was given, as JPA's persist does for a new entity
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Category result = categoryService.findOrCreateCategory("Tribal Art");

        // Assert: the service builds the Category itself, so the test cannot hold the same object to compare with;
        // an ArgumentCaptor catches the object that was really passed to save()
        ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(saved.capture());
        assertEquals("Tribal Art", saved.getValue().getName());
        assertSame(saved.getValue(), result);
    }

    @Test
    void findOrCreateCategory_whenAnotherRequestCreatedItFirst_returnsTheirCategory() {
        // Arrange: the first lookup finds nothing, our insert then hits the unique name constraint because a
        // concurrent request inserted the same name, and the second lookup finds the row that request committed
        Category createdByOtherRequest = category("Tribal Art");
        when(categoryRepository.findByName("Tribal Art"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(createdByOtherRequest));
        when(categoryRepository.save(any(Category.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry 'Tribal Art' for key 'uk_category_name'"));

        // Act
        Category result = categoryService.findOrCreateCategory("Tribal Art");

        // Assert
        assertSame(createdByOtherRequest, result);
        verify(categoryRepository, times(2)).findByName("Tribal Art");
    }
}
