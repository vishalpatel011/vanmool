package com.vanmool.productservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatch;
import com.vanmool.productservice.configs.CacheConfig;
import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.dtos.ProductResponseDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Category;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.repositories.ProductRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Tests the caching annotations on ProductDbService: which method reads the cache, under which key, and which
// methods remove an entry. Spring's caching proxy wraps the real service, as in the application, but the cache is an
// in-memory map and the repository a mock, so no Redis or MySQL is needed. Redis itself: ProductCacheIT.
@SpringJUnitConfig
class ProductCacheTest {
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final UUID BISCUITS_ID = UUID.fromString("6f1c2a9e-3b7d-4c55-9a8e-2d4b6c8e0f12");

    @Configuration
    @EnableCaching
    static class Config {
        // Only the product cache exists, as with disableCreateOnMissingCache() in CacheConfig
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CacheConfig.PRODUCT_CACHE);
        }

        @Bean
        ProductDbService productDbService(CategoryService categoryService, ProductRepository productRepository) {
            return new ProductDbService(categoryService, productRepository,
                    Validation.buildDefaultValidatorFactory().getValidator(), event -> { });
        }
    }

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private CategoryService categoryService;

    @Autowired
    private ProductService productService;

    @Autowired
    private CacheManager cacheManager;

    private Cache cache;

    private static Product product(UUID id, String name) {
        Category category = new Category();
        category.setName("Traditional Crafts");
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setPrice(new BigDecimal("499.99"));
        product.setCategory(category);
        return product;
    }

    private static ProductRequestDto request(String name) {
        ProductRequestDto request = new ProductRequestDto();
        request.setName(name);
        request.setPrice(new BigDecimal("549.00"));
        request.setCategory("Traditional Crafts");
        return request;
    }

    private static JsonPatch patch(String json) throws Exception {
        return JsonPatch.fromJson(new ObjectMapper().readTree(json));
    }

    @BeforeEach
    void setUp() {
        cache = cacheManager.getCache(CacheConfig.PRODUCT_CACHE);
        cache.clear();
        when(productRepository.findByIdAndDeletedFalse(BASKET_ID))
                .thenAnswer(invocation -> Optional.of(product(BASKET_ID, "Handcrafted Bamboo Basket")));
        when(productRepository.findByIdAndDeletedFalse(BISCUITS_ID))
                .thenAnswer(invocation -> Optional.of(product(BISCUITS_ID, "Ragi Biscuits")));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(categoryService.findOrCreateCategory(any())).thenReturn(new Category());
    }

    @Test
    void secondRead_isServedFromTheCache_withoutQueryingTheDatabase() throws ProductNotFoundException {
        // Act
        ProductResponseDto first = productService.getProductById(BASKET_ID);
        ProductResponseDto second = productService.getProductById(BASKET_ID);

        // Assert: one query, and the entry is stored under the product id
        verify(productRepository, times(1)).findByIdAndDeletedFalse(BASKET_ID);
        assertEquals("Handcrafted Bamboo Basket", second.getName());
        assertEquals(first.getName(), second.getName());
        assertNotNull(cache.get(BASKET_ID));
    }

    @Test
    void missingProduct_isNotCached_soEveryReadAsksTheDatabaseAgain() {
        // Arrange
        UUID missingId = UUID.randomUUID();
        when(productRepository.findByIdAndDeletedFalse(missingId)).thenReturn(Optional.empty());

        // Act & Assert: a product created later under this id must not be hidden by a cached "not found"
        assertThrows(ProductNotFoundException.class, () -> productService.getProductById(missingId));
        assertThrows(ProductNotFoundException.class, () -> productService.getProductById(missingId));
        verify(productRepository, times(2)).findByIdAndDeletedFalse(missingId);
        assertNull(cache.get(missingId));
    }

    @Test
    void replace_removesOnlyThatProductsEntry() throws ProductNotFoundException {
        // Arrange
        productService.getProductById(BASKET_ID);
        productService.getProductById(BISCUITS_ID);

        // Act
        productService.replaceProduct(BASKET_ID, request("Handcrafted Bamboo Basket, large"));

        // Assert
        assertNull(cache.get(BASKET_ID));
        assertNotNull(cache.get(BISCUITS_ID));
    }

    @Test
    void patch_removesTheEntry() throws Exception {
        // Arrange
        productService.getProductById(BASKET_ID);

        // Act
        productService.patchProduct(BASKET_ID, patch("[{\"op\": \"replace\", \"path\": \"/price\", \"value\": 450.00}]"));

        // Assert
        assertNull(cache.get(BASKET_ID));
    }

    @Test
    void delete_removesTheEntry_soTheProductIsNoLongerServedFromTheCache() throws ProductNotFoundException {
        // Arrange
        productService.getProductById(BASKET_ID);

        // Act
        productService.deleteProduct(BASKET_ID);

        // Assert: the next read goes to the database, which no longer returns a deleted product
        assertNull(cache.get(BASKET_ID));
        when(productRepository.findByIdAndDeletedFalse(BASKET_ID)).thenReturn(Optional.empty());
        assertThrows(ProductNotFoundException.class, () -> productService.getProductById(BASKET_ID));
    }

    @Test
    void rejectedChange_keepsTheEntry() throws Exception {
        // Arrange
        productService.getProductById(BASKET_ID);

        // Act: a negative price breaks a validation rule, so nothing is saved
        assertThrows(ConstraintViolationException.class, () -> productService.patchProduct(BASKET_ID,
                patch("[{\"op\": \"replace\", \"path\": \"/price\", \"value\": -1}]")));

        // Assert
        assertNotNull(cache.get(BASKET_ID));
    }
}
