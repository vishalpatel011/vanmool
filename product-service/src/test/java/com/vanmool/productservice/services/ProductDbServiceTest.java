package com.vanmool.productservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Category;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.repositories.ProductRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Plain unit test: the repository and CategoryService are Mockito mocks, so no database or Spring context is needed.
// The Validator is the real Hibernate Validator, because the validation rules are part of the behaviour under test.
@ExtendWith(MockitoExtension.class)
class ProductDbServiceTest {
    private static final UUID PRODUCT_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryService categoryService;

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProductDbService productDbService;

    @BeforeEach
    void setUp() {
        productDbService = new ProductDbService(categoryService, productRepository, validator);
    }

    private static Category category(String name) {
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName(name);
        return category;
    }

    private static Product bambooBasket() {
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setName("Handcrafted Bamboo Basket");
        product.setDescription("Woven by hand from local bamboo in Dangs, Gujarat");
        product.setPrice(new BigDecimal("499.99"));
        product.setImageUrl("https://example.com/images/bamboo-basket.jpg");
        product.setCategory(category("Traditional Crafts"));
        return product;
    }

    private JsonPatch patch(String json) throws IOException {
        return JsonPatch.fromJson(objectMapper.readTree(json));
    }

    // save() returns the entity it was given, as JPA does for an entity in the current persistence context
    private void saveReturnsItsArgument() {
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void getProductById_whenProductExists_returnsIt() throws ProductNotFoundException {
        // Arrange
        Product basket = bambooBasket();
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(basket));

        // Act
        Product result = productDbService.getProductById(PRODUCT_ID);

        // Assert
        assertSame(basket, result);
    }

    @Test
    void getProductById_whenProductIsMissingOrDeleted_throwsProductNotFoundException() {
        // Arrange: the repository skips deleted rows, so a deleted product also comes back empty
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.empty());

        // Act & Assert
        ProductNotFoundException exception = assertThrows(ProductNotFoundException.class,
                () -> productDbService.getProductById(PRODUCT_ID));
        assertTrue(exception.getMessage().contains(PRODUCT_ID.toString()));
    }

    @Test
    void createProduct_savesAProductWithEveryFieldAndItsCategory() {
        // Arrange
        Category naturalFoods = category("Natural Foods");
        when(categoryService.findOrCreateCategory("Natural Foods")).thenReturn(naturalFoods);
        saveReturnsItsArgument();

        // Act
        Product result = productDbService.createProduct("Ragi Biscuits", "Baked with finger millet from Gujarat",
                new BigDecimal("120.00"), "https://example.com/images/ragi-biscuits.jpg", "Natural Foods");

        // Assert: the service creates the Product itself, so an ArgumentCaptor catches what was saved
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertSame(saved.getValue(), result);
        assertAll(
                () -> assertEquals("Ragi Biscuits", result.getName()),
                () -> assertEquals("Baked with finger millet from Gujarat", result.getDescription()),
                () -> assertEquals(new BigDecimal("120.00"), result.getPrice()),
                () -> assertEquals("https://example.com/images/ragi-biscuits.jpg", result.getImageUrl()),
                () -> assertSame(naturalFoods, result.getCategory()),
                () -> assertFalse(result.isDeleted())
        );
    }

    @Test
    void createProduct_withoutCategory_savesProductWithNoCategory() {
        // Arrange: CategoryService answers null for a missing name
        when(categoryService.findOrCreateCategory(null)).thenReturn(null);
        saveReturnsItsArgument();

        // Act
        Product result = productDbService.createProduct("Wooden Spinning Top", null, new BigDecimal("180"), null, null);

        // Assert
        assertNull(result.getCategory());
    }

    @Test
    void replaceProduct_whenProductExists_replacesEveryFieldAndKeepsTheId() throws ProductNotFoundException {
        // Arrange
        Product basket = bambooBasket();
        Category tribalArt = category("Tribal Art");
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(basket));
        when(categoryService.findOrCreateCategory("Tribal Art")).thenReturn(tribalArt);
        saveReturnsItsArgument();

        // Act
        Product result = productDbService.replaceProduct(PRODUCT_ID, "Warli Painted Basket", null,
                new BigDecimal("650.00"), null, "Tribal Art");

        // Assert: PUT replaces the whole product, so fields left out of the request become null
        assertAll(
                () -> assertEquals(PRODUCT_ID, result.getId()),
                () -> assertEquals("Warli Painted Basket", result.getName()),
                () -> assertNull(result.getDescription()),
                () -> assertEquals(new BigDecimal("650.00"), result.getPrice()),
                () -> assertNull(result.getImageUrl()),
                () -> assertSame(tribalArt, result.getCategory())
        );
        verify(productRepository).save(basket);
    }

    @Test
    void replaceProduct_whenProductIsMissing_throwsAndCreatesNothing() {
        // Arrange
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.empty());

        // Act & Assert: ids are generated by the database, so PUT never creates a product at a client-chosen id
        assertThrows(ProductNotFoundException.class, () -> productDbService.replaceProduct(PRODUCT_ID,
                "Warli Painted Basket", null, new BigDecimal("650.00"), null, "Tribal Art"));
        verify(productRepository, never()).save(any());
        verifyNoInteractions(categoryService);
    }

    @Test
    void patchProduct_changesOnlyThePatchedField() throws Exception {
        // Arrange
        Product basket = bambooBasket();
        Category traditionalCrafts = basket.getCategory();
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(basket));
        when(categoryService.findOrCreateCategory("Traditional Crafts")).thenReturn(traditionalCrafts);
        saveReturnsItsArgument();

        // Act
        Product result = productDbService.patchProduct(PRODUCT_ID,
                patch("[{\"op\": \"replace\", \"path\": \"/price\", \"value\": 450}]"));

        // Assert
        assertAll(
                () -> assertEquals(0, new BigDecimal("450").compareTo(result.getPrice())),
                () -> assertEquals("Handcrafted Bamboo Basket", result.getName()),
                () -> assertEquals("Woven by hand from local bamboo in Dangs, Gujarat", result.getDescription()),
                () -> assertEquals("https://example.com/images/bamboo-basket.jpg", result.getImageUrl()),
                () -> assertSame(traditionalCrafts, result.getCategory())
        );
    }

    @Test
    void patchProduct_cannotChangeTheId() throws Exception {
        // Arrange
        Product basket = bambooBasket();
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(basket));
        when(categoryService.findOrCreateCategory("Traditional Crafts")).thenReturn(basket.getCategory());
        saveReturnsItsArgument();

        // Act
        Product result = productDbService.patchProduct(PRODUCT_ID, patch(
                "[{\"op\": \"replace\", \"path\": \"/id\", \"value\": \"00000000-0000-0000-0000-000000000001\"}]"));

        // Assert
        assertEquals(PRODUCT_ID, result.getId());
    }

    @Test
    void patchProduct_whenResultBreaksAValidationRule_throwsAndSavesNothing() throws Exception {
        // Arrange
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(bambooBasket()));
        JsonPatch negativePrice = patch("[{\"op\": \"replace\", \"path\": \"/price\", \"value\": -5}]");

        // Act
        ConstraintViolationException exception = assertThrows(ConstraintViolationException.class,
                () -> productDbService.patchProduct(PRODUCT_ID, negativePrice));

        // Assert: check which field failed, not the message text, which depends on the machine's locale
        assertEquals("price", exception.getConstraintViolations().stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString).findFirst().orElseThrow());
        verify(productRepository, never()).save(any());
    }

    @Test
    void patchProduct_whenPatchPathDoesNotExist_throwsJsonPatchException() throws Exception {
        // Arrange
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(bambooBasket()));
        JsonPatch badPath = patch("[{\"op\": \"replace\", \"path\": \"/origin/region\", \"value\": \"Dangs\"}]");

        // Act & Assert
        assertThrows(JsonPatchException.class, () -> productDbService.patchProduct(PRODUCT_ID, badPath));
        verify(productRepository, never()).save(any());
    }

    @Test
    void patchProduct_whenProductIsMissing_throwsProductNotFoundException() throws Exception {
        // Arrange
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.empty());
        JsonPatch priceChange = patch("[{\"op\": \"replace\", \"path\": \"/price\", \"value\": 450}]");

        // Act & Assert
        assertThrows(ProductNotFoundException.class, () -> productDbService.patchProduct(PRODUCT_ID, priceChange));
        verify(productRepository, never()).save(any());
    }

    @Test
    void deleteProduct_marksTheProductDeletedInsteadOfRemovingTheRow() throws ProductNotFoundException {
        // Arrange
        Product basket = bambooBasket();
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(basket));

        // Act
        productDbService.deleteProduct(PRODUCT_ID);

        // Assert: orders and other services keep product ids, so the row must stay
        assertTrue(basket.isDeleted());
        verify(productRepository).save(basket);
        verify(productRepository, never()).delete(any());
        verify(productRepository, never()).deleteById(any());
    }

    @Test
    void deleteProduct_whenProductIsMissing_throwsAndSavesNothing() {
        // Arrange
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ProductNotFoundException.class, () -> productDbService.deleteProduct(PRODUCT_ID));
        verify(productRepository, never()).save(any());
    }
}
