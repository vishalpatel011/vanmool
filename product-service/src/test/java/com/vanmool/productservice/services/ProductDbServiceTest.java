package com.vanmool.productservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
import com.vanmool.productservice.dtos.ProductImageDto;
import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.dtos.ProductResponseDto;
import com.vanmool.productservice.dtos.ProductSpecificationDto;
import com.vanmool.productservice.exceptions.InvalidRequestException;
import com.vanmool.productservice.exceptions.InvalidSortException;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Category;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.models.ProductImage;
import com.vanmool.productservice.models.ProductSpecification;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        product.setImages(List.of(
                new ProductImage("https://example.com/images/bamboo-basket.jpg", "Handcrafted Bamboo Basket"),
                new ProductImage("https://example.com/images/bamboo-basket-weave.jpg", "The weave up close")));
        product.setSpecifications(List.of(
                new ProductSpecification("Dimensions", "30 x 20 x 15 cm"),
                new ProductSpecification("Weight", "400 g")));
        product.setCategory(category("Traditional Crafts"));
        product.setOriginRegion("Dangs, Gujarat");
        product.setMaterials("Bamboo");
        product.setStory("Woven by artisans in the Dangs forests");
        return product;
    }

    private static ProductRequestDto request(String name, String description, String price, String imageUrl,
                                             String category) {
        ProductRequestDto request = new ProductRequestDto();
        request.setName(name);
        request.setDescription(description);
        request.setPrice(new BigDecimal(price));
        request.setImages(imageUrl == null ? null : List.of(new ProductImageDto(imageUrl, null)));
        request.setCategory(category);
        return request;
    }

    private JsonPatch patch(String json) throws IOException {
        return JsonPatch.fromJson(objectMapper.readTree(json));
    }

    // save() returns the entity it was given, as JPA does for an entity in the current persistence context
    private void saveReturnsItsArgument() {
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void getProductById_whenProductExists_returnsEveryFieldAsTheResponse() throws ProductNotFoundException {
        // Arrange
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(bambooBasket()));

        // Act
        ProductResponseDto response = productDbService.getProductById(PRODUCT_ID);

        // Assert
        assertAll(
                () -> assertEquals(PRODUCT_ID, response.getId()),
                () -> assertEquals("Handcrafted Bamboo Basket", response.getName()),
                () -> assertEquals("Woven by hand from local bamboo in Dangs, Gujarat", response.getDescription()),
                // The main image is the first of the images
                () -> assertEquals("https://example.com/images/bamboo-basket.jpg", response.getImageUrl()),
                () -> assertEquals(List.of(
                        new ProductImageDto("https://example.com/images/bamboo-basket.jpg", "Handcrafted Bamboo Basket"),
                        new ProductImageDto("https://example.com/images/bamboo-basket-weave.jpg", "The weave up close")),
                        response.getImages()),
                () -> assertEquals(List.of(new ProductSpecificationDto("Dimensions", "30 x 20 x 15 cm"),
                        new ProductSpecificationDto("Weight", "400 g")), response.getSpecifications()),
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
        Product top = bambooBasket();
        top.setCategory(null);
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(top));

        // Act & Assert
        assertNull(productDbService.getProductById(PRODUCT_ID).getCategory());
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
    void searchProducts_turnsTextIntoContainsPatternsAndBlankFiltersIntoNull() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(productRepository.search(any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        // Act
        productDbService.searchProducts(new ProductSearchCriteria("  bamboo ", "   ", "Dangs",
                new BigDecimal("100"), null), pageable);

        // Assert: a blank filter is switched off (null), and words become "contains" patterns
        verify(productRepository).search(eq("%bamboo%"), isNull(), eq("%Dangs%"), eq(new BigDecimal("100")), isNull(), any(Pageable.class));
    }

    @Test
    void searchProducts_escapesLikeWildcardsTypedByTheUser() {
        // Arrange
        when(productRepository.search(any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        // Act
        productDbService.searchProducts(new ProductSearchCriteria("100%_off!", null, null, null, null), PageRequest.of(0, 20));

        // Assert: % and _ are LIKE wildcards and ! is the escape character, so each is escaped with !
        verify(productRepository).search(eq("%100!%!_off!!%"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void searchProducts_addsTheIdAsTheLastSortKeySoPagesAreStable() {
        // Arrange
        when(productRepository.search(any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        // Act
        productDbService.searchProducts(new ProductSearchCriteria(null, null, null, null, null),
                PageRequest.of(3, 10, Sort.by(Sort.Direction.ASC, "price")));

        // Assert
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).search(isNull(), isNull(), isNull(), isNull(), isNull(), pageable.capture());
        assertEquals(PageRequest.of(3, 10, Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id"))), pageable.getValue());
    }

    @Test
    void searchProducts_sortingByAFieldThatIsNotAllowed_throwsWithoutQuerying() {
        // Act & Assert: deleted exists on the entity but is not a sort option for clients
        assertThrows(InvalidSortException.class, () -> productDbService.searchProducts(
                new ProductSearchCriteria(null, null, null, null, null), PageRequest.of(0, 20, Sort.by("deleted"))));
        verifyNoInteractions(productRepository);
    }

    @Test
    void searchProducts_returnsTheRepositorysPage() {
        // Arrange
        Page<Product> page = new PageImpl<>(List.of(bambooBasket()));
        when(productRepository.search(any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(page);

        // Act & Assert
        assertSame(page, productDbService.searchProducts(new ProductSearchCriteria("basket", null, null, null, null),
                PageRequest.of(0, 20)));
    }

    @Test
    void createProduct_savesAProductWithEveryFieldAndItsCategory() {
        // Arrange
        Category naturalFoods = category("Natural Foods");
        when(categoryService.findOrCreateCategory("Natural Foods")).thenReturn(naturalFoods);
        saveReturnsItsArgument();

        ProductRequestDto ragiBiscuits = request("Ragi Biscuits", "Baked with finger millet from Gujarat",
                "120.00", "https://example.com/images/ragi-biscuits.jpg", "Natural Foods");
        ragiBiscuits.setOriginRegion("Gujarat");
        ragiBiscuits.setMaterials("Finger millet flour, jaggery");
        ragiBiscuits.setStory("Baked by a women's self-help group");

        // Act
        Product result = productDbService.createProduct(ragiBiscuits);

        // Assert: the service creates the Product itself, so an ArgumentCaptor catches what was saved
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertSame(saved.getValue(), result);
        assertAll(
                () -> assertEquals("Ragi Biscuits", result.getName()),
                () -> assertEquals("Baked with finger millet from Gujarat", result.getDescription()),
                () -> assertEquals(new BigDecimal("120.00"), result.getPrice()),
                () -> assertEquals(List.of(new ProductImage("https://example.com/images/ragi-biscuits.jpg", null)), result.getImages()),
                () -> assertEquals(List.of(), result.getSpecifications()),
                () -> assertSame(naturalFoods, result.getCategory()),
                () -> assertEquals("Gujarat", result.getOriginRegion()),
                () -> assertEquals("Finger millet flour, jaggery", result.getMaterials()),
                () -> assertEquals("Baked by a women's self-help group", result.getStory()),
                () -> assertFalse(result.isDeleted())
        );
    }

    @Test
    void createProduct_withoutCategory_savesProductWithNoCategory() {
        // Arrange: CategoryService answers null for a missing name
        when(categoryService.findOrCreateCategory(null)).thenReturn(null);
        saveReturnsItsArgument();

        // Act
        Product result = productDbService.createProduct(request("Wooden Spinning Top", null, "180", null, null));

        // Assert
        assertNull(result.getCategory());
        assertEquals(List.of(), result.getImages());
        assertNull(result.mainImageUrl());
    }

    @Test
    void createProduct_keepsImagesAndSpecificationsInTheirOrder_trimmed() {
        // Arrange
        when(categoryService.findOrCreateCategory(null)).thenReturn(null);
        saveReturnsItsArgument();
        ProductRequestDto top = request("Wooden Spinning Top", null, "180", null, null);
        top.setImages(List.of(new ProductImageDto(" https://example.com/images/top.jpg ", "  "),
                new ProductImageDto("https://example.com/images/top-spinning.jpg", "The top spinning on a floor")));
        top.setSpecifications(List.of(new ProductSpecificationDto(" Wood ", " Teak "),
                new ProductSpecificationDto("Suitable for", "Children over 3 years")));

        // Act
        Product result = productDbService.createProduct(top);

        // Assert: a blank description of an image is stored as none
        assertEquals(List.of(new ProductImage("https://example.com/images/top.jpg", null),
                new ProductImage("https://example.com/images/top-spinning.jpg", "The top spinning on a floor")), result.getImages());
        assertEquals("https://example.com/images/top.jpg", result.mainImageUrl());
        assertEquals(List.of(new ProductSpecification("Wood", "Teak"),
                new ProductSpecification("Suitable for", "Children over 3 years")), result.getSpecifications());
    }

    @Test
    void createProduct_withTheSameSpecificationTwice_inAnyLetterCase_isRefused_andNothingIsSaved() {
        ProductRequestDto biscuits = request("Ragi Biscuits", null, "120", null, null);
        biscuits.setSpecifications(List.of(new ProductSpecificationDto("Weight", "200 g"),
                new ProductSpecificationDto("weight ", "250 g")));

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> productDbService.createProduct(biscuits));

        assertEquals("specifications: \"weight\" appears more than once", exception.getMessage());
        verify(productRepository, never()).save(any());
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
        Product result = productDbService.replaceProduct(PRODUCT_ID,
                request("Warli Painted Basket", null, "650.00", null, "Tribal Art"));

        // Assert: PUT replaces the whole product, so fields left out of the request become null
        assertAll(
                () -> assertEquals(PRODUCT_ID, result.getId()),
                () -> assertEquals("Warli Painted Basket", result.getName()),
                () -> assertNull(result.getDescription()),
                () -> assertEquals(new BigDecimal("650.00"), result.getPrice()),
                // and lists left out become empty: the basket's two images and two specifications are gone
                () -> assertEquals(List.of(), result.getImages()),
                () -> assertEquals(List.of(), result.getSpecifications()),
                () -> assertSame(tribalArt, result.getCategory()),
                () -> assertNull(result.getOriginRegion()),
                () -> assertNull(result.getMaterials()),
                () -> assertNull(result.getStory())
        );
        verify(productRepository).save(basket);
    }

    @Test
    void replaceProduct_whenProductIsMissing_throwsAndCreatesNothing() {
        // Arrange
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.empty());

        // Act & Assert: ids are generated by the database, so PUT never creates a product at a client-chosen id
        ProductRequestDto replacement = request("Warli Painted Basket", null, "650.00", null, "Tribal Art");
        assertThrows(ProductNotFoundException.class, () -> productDbService.replaceProduct(PRODUCT_ID, replacement));
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
                () -> assertEquals(2, result.getImages().size()),
                () -> assertEquals("https://example.com/images/bamboo-basket.jpg", result.mainImageUrl()),
                () -> assertEquals(List.of(new ProductSpecification("Dimensions", "30 x 20 x 15 cm"),
                        new ProductSpecification("Weight", "400 g")), result.getSpecifications()),
                () -> assertSame(traditionalCrafts, result.getCategory()),
                () -> assertEquals("Dangs, Gujarat", result.getOriginRegion()),
                () -> assertEquals("Bamboo", result.getMaterials())
        );
    }

    @Test
    void patchProduct_canAddAnImageAndASpecification_atTheEndOfTheirLists() throws Exception {
        // Arrange
        Product basket = bambooBasket();
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(basket));
        when(categoryService.findOrCreateCategory("Traditional Crafts")).thenReturn(basket.getCategory());
        saveReturnsItsArgument();

        // Act: "-" is JSON Patch for "after the last element"
        Product result = productDbService.patchProduct(PRODUCT_ID, patch("""
                [{"op": "add", "path": "/images/-", "value": {"url": "https://example.com/images/basket-in-use.jpg", "altText": "Fruit in the basket"}},
                 {"op": "add", "path": "/specifications/-", "value": {"name": "Care", "value": "Wipe with a dry cloth"}}]"""));

        // Assert
        assertEquals(3, result.getImages().size());
        assertEquals(new ProductImage("https://example.com/images/basket-in-use.jpg", "Fruit in the basket"), result.getImages().get(2));
        assertEquals(new ProductSpecification("Care", "Wipe with a dry cloth"), result.getSpecifications().get(2));
        assertEquals("https://example.com/images/bamboo-basket.jpg", result.mainImageUrl());
    }

    @Test
    void patchProduct_cannotSetTheMainImageDirectly_itIsAlwaysTheFirstImage() throws Exception {
        // Arrange
        Product basket = bambooBasket();
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(basket));
        when(categoryService.findOrCreateCategory("Traditional Crafts")).thenReturn(basket.getCategory());
        saveReturnsItsArgument();

        // Act
        Product result = productDbService.patchProduct(PRODUCT_ID,
                patch("[{\"op\": \"replace\", \"path\": \"/imageUrl\", \"value\": \"https://example.com/other.jpg\"}]"));

        // Assert
        assertEquals("https://example.com/images/bamboo-basket.jpg", result.mainImageUrl());
        assertEquals(2, result.getImages().size());
    }

    @Test
    void patchProduct_withAnImageAddressThatIsNotHttp_throwsNamingTheElement() throws Exception {
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(bambooBasket()));
        JsonPatch scriptUrl = patch("[{\"op\": \"replace\", \"path\": \"/images/1/url\", \"value\": \"javascript:alert(1)\"}]");

        ConstraintViolationException exception = assertThrows(ConstraintViolationException.class,
                () -> productDbService.patchProduct(PRODUCT_ID, scriptUrl));

        assertEquals("images[1].url", exception.getConstraintViolations().stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString).findFirst().orElseThrow());
        verify(productRepository, never()).save(any());
    }

    @Test
    void patchProduct_canSetAProvenanceFieldThatWasEmpty() throws Exception {
        // Arrange: a product created before migration V3 has no origin yet
        Product basket = bambooBasket();
        basket.setOriginRegion(null);
        when(productRepository.findByIdAndDeletedFalse(PRODUCT_ID)).thenReturn(Optional.of(basket));
        when(categoryService.findOrCreateCategory("Traditional Crafts")).thenReturn(basket.getCategory());
        saveReturnsItsArgument();

        // Act
        Product result = productDbService.patchProduct(PRODUCT_ID,
                patch("[{\"op\": \"replace\", \"path\": \"/originRegion\", \"value\": \"Dangs, Gujarat\"}]"));

        // Assert
        assertEquals("Dangs, Gujarat", result.getOriginRegion());
        assertEquals(new BigDecimal("499.99"), result.getPrice());
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
