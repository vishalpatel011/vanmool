package com.vanmool.productservice.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatch;
import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.dtos.ProductResponseDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Category;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.services.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Unit test of the controller as a plain Java class: ProductService is a mock, and no web server or Spring context
// is started. What it checks is the controller's own logic: choosing the right service call and mapping entities to
// DTOs. HTTP details (JSON, status codes on the wire, validation) are covered by MockMvc tests.
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {
    private static final UUID PRODUCT_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");

    @Mock
    private ProductService productService;

    @InjectMocks
    private ProductController productController;

    private static Product product(String name, String categoryName) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName(name);
        product.setPrice(new BigDecimal("100.00"));
        if (categoryName != null) {
            Category category = new Category();
            category.setName(categoryName);
            product.setCategory(category);
        }
        return product;
    }

    private static List<String> names(List<ProductResponseDto> productResponseDtos) {
        return productResponseDtos.stream().map(ProductResponseDto::getName).toList();
    }

    @Test
    void getProductById_mapsEveryFieldOfTheProductToTheResponse() throws ProductNotFoundException {
        // Arrange
        Product basket = product("Handcrafted Bamboo Basket", "Traditional Crafts");
        basket.setId(PRODUCT_ID);
        basket.setDescription("Woven by hand from local bamboo in Dangs, Gujarat");
        basket.setImageUrl("https://example.com/images/bamboo-basket.jpg");
        basket.setPrice(new BigDecimal("499.99"));
        basket.setOriginRegion("Dangs, Gujarat");
        basket.setMaterials("Bamboo");
        basket.setStory("Woven by artisans in the Dangs forests");
        when(productService.getProductById(PRODUCT_ID)).thenReturn(basket);

        // Act
        ProductResponseDto response = productController.getProductById(PRODUCT_ID);

        // Assert
        assertAll(
                () -> assertEquals(PRODUCT_ID, response.getId()),
                () -> assertEquals("Handcrafted Bamboo Basket", response.getName()),
                () -> assertEquals("Woven by hand from local bamboo in Dangs, Gujarat", response.getDescription()),
                () -> assertEquals("https://example.com/images/bamboo-basket.jpg", response.getImageUrl()),
                () -> assertEquals(new BigDecimal("499.99"), response.getPrice()),
                () -> assertEquals("Traditional Crafts", response.getCategory()),
                () -> assertEquals("Dangs, Gujarat", response.getOriginRegion()),
                () -> assertEquals("Bamboo", response.getMaterials()),
                () -> assertEquals("Woven by artisans in the Dangs forests", response.getStory())
        );
    }

    @Test
    void getProductById_whenProductHasNoCategory_returnsNullCategory() throws ProductNotFoundException {
        // Arrange
        when(productService.getProductById(PRODUCT_ID)).thenReturn(product("Wooden Spinning Top", null));

        // Act
        ProductResponseDto response = productController.getProductById(PRODUCT_ID);

        // Assert
        assertNull(response.getCategory());
    }

    @Test
    void getProductById_whenServiceThrowsNotFound_passesTheExceptionOn() throws ProductNotFoundException {
        // Arrange
        when(productService.getProductById(PRODUCT_ID))
                .thenThrow(new ProductNotFoundException("Product with id " + PRODUCT_ID + " not found"));

        // Act & Assert: GlobalExceptionHandler turns this exception into a 404 response
        assertThrows(ProductNotFoundException.class, () -> productController.getProductById(PRODUCT_ID));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void getAllProducts_withoutACategory_returnsEveryProduct(String category) {
        // Arrange
        when(productService.getAllProducts()).thenReturn(List.of(
                product("Handcrafted Bamboo Basket", "Traditional Crafts"),
                product("Ragi Biscuits", "Natural Foods")));

        // Act
        List<ProductResponseDto> response = productController.getAllProducts(category);

        // Assert
        assertEquals(List.of("Handcrafted Bamboo Basket", "Ragi Biscuits"), names(response));
    }

    @Test
    void getAllProducts_withACategory_returnsOnlyThatCategorysProducts() {
        // Arrange
        when(productService.getProductsByCategory("Natural Foods"))
                .thenReturn(List.of(product("Ragi Biscuits", "Natural Foods")));

        // Act
        List<ProductResponseDto> response = productController.getAllProducts("Natural Foods");

        // Assert
        assertEquals(List.of("Ragi Biscuits"), names(response));
    }

    @Test
    void createProduct_passesTheRequestToTheServiceAndAnswers201WithTheLocation() {
        // Arrange
        ProductRequestDto request = new ProductRequestDto();
        request.setName("Ragi Biscuits");
        request.setPrice(new BigDecimal("120.00"));
        request.setDescription("Baked with finger millet from Gujarat");
        request.setImageUrl("https://example.com/images/ragi-biscuits.jpg");
        request.setCategory("Natural Foods");
        Product created = product("Ragi Biscuits", "Natural Foods");
        when(productService.createProduct(request)).thenReturn(created);

        // Act: outside a real request, the test supplies the base URL Spring would otherwise take from the request
        ResponseEntity<ProductResponseDto> response = productController.createProduct(request,
                UriComponentsBuilder.fromUriString("http://localhost:8080"));

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(created.getId(), response.getBody().getId());
        assertEquals(URI.create("http://localhost:8080/products/" + created.getId()), response.getHeaders().getLocation());
    }

    @Test
    void replaceProduct_passesTheRequestToTheServiceAndMapsTheResult() throws ProductNotFoundException {
        // Arrange: the controller hands the validated request object to the service unchanged
        ProductRequestDto request = new ProductRequestDto();
        request.setName("Warli Painted Basket");
        request.setPrice(new BigDecimal("650.00"));
        request.setDescription("Painted with Warli motifs");
        request.setImageUrl("https://example.com/images/warli-basket.jpg");
        request.setCategory("Tribal Art");
        Product replaced = product("Warli Painted Basket", "Tribal Art");
        replaced.setId(PRODUCT_ID);
        when(productService.replaceProduct(PRODUCT_ID, request)).thenReturn(replaced);

        // Act
        ProductResponseDto response = productController.replaceProduct(PRODUCT_ID, request);

        // Assert
        assertEquals(PRODUCT_ID, response.getId());
        assertEquals("Tribal Art", response.getCategory());
    }

    @Test
    void patchProduct_returnsThePatchedProduct() throws Exception {
        // Arrange
        JsonPatch priceChange = JsonPatch.fromJson(new ObjectMapper()
                .readTree("[{\"op\": \"replace\", \"path\": \"/price\", \"value\": 450}]"));
        Product patched = product("Handcrafted Bamboo Basket", "Traditional Crafts");
        patched.setPrice(new BigDecimal("450"));
        when(productService.patchProduct(PRODUCT_ID, priceChange)).thenReturn(patched);

        // Act
        ProductResponseDto response = productController.patchProduct(PRODUCT_ID, priceChange);

        // Assert
        assertEquals(new BigDecimal("450"), response.getPrice());
    }

    @Test
    void deleteProduct_deletesThroughTheServiceAndAnswers204() throws ProductNotFoundException {
        // Act
        ResponseEntity<Void> response = productController.deleteProduct(PRODUCT_ID);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(productService).deleteProduct(PRODUCT_ID);
    }
}
