package com.vanmool.productservice.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatch;
import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.dtos.ProductResponseDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Category;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.services.ProductSearchCriteria;
import com.vanmool.productservice.services.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void getProductById_returnsTheServiceResponseAsItIs() throws ProductNotFoundException {
        // Arrange: the service already returns the DTO (it is what the product cache holds)
        ProductResponseDto basket = ProductResponseDto.from(product("Handcrafted Bamboo Basket", "Traditional Crafts"));
        when(productService.getProductById(PRODUCT_ID)).thenReturn(basket);

        // Act
        ProductResponseDto response = productController.getProductById(PRODUCT_ID);

        // Assert
        assertSame(basket, response);
    }

    @Test
    void getProductById_whenServiceThrowsNotFound_passesTheExceptionOn() throws ProductNotFoundException {
        // Arrange
        when(productService.getProductById(PRODUCT_ID))
                .thenThrow(new ProductNotFoundException("Product with id " + PRODUCT_ID + " not found"));

        // Act & Assert: GlobalExceptionHandler turns this exception into a 404 response
        assertThrows(ProductNotFoundException.class, () -> productController.getProductById(PRODUCT_ID));
    }

    @Test
    void getProducts_passesEveryFilterToTheServiceAsOneCriteria() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 20, Sort.by("price"));
        ProductSearchCriteria expected = new ProductSearchCriteria("bamboo", "Traditional Crafts", "Dangs",
                new BigDecimal("100"), new BigDecimal("600"));
        when(productService.searchProducts(expected, pageable)).thenReturn(Page.empty(pageable));

        // Act: a record has value equality, so the stub matches the equal criteria object the controller builds
        Page<ProductResponseDto> response = productController.getProducts("bamboo", "Traditional Crafts", "Dangs",
                new BigDecimal("100"), new BigDecimal("600"), pageable);

        // Assert
        assertEquals(0, response.getTotalElements());
    }

    @Test
    void getProducts_mapsEachProductAndKeepsThePageInformation() {
        // Arrange: the second page of 3 products, 2 per page, so it holds only the third
        Pageable secondPage = PageRequest.of(1, 2);
        Page<Product> page = new PageImpl<>(List.of(product("Ragi Biscuits", "Natural Foods")), secondPage, 3);
        when(productService.searchProducts(new ProductSearchCriteria(null, null, null, null, null), secondPage)).thenReturn(page);

        // Act
        Page<ProductResponseDto> response = productController.getProducts(null, null, null, null, null, secondPage);

        // Assert
        assertEquals(List.of("Ragi Biscuits"), names(response.getContent()));
        assertEquals("Natural Foods", response.getContent().get(0).getCategory());
        assertEquals(1, response.getNumber());
        assertEquals(3, response.getTotalElements());
        assertEquals(2, response.getTotalPages());
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
