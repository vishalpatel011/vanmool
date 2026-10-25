package com.vanmool.productservice.controllers;

import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Category;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.services.ProductService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Web layer test: @WebMvcTest starts only Spring MVC for ProductController (request mapping, JSON conversion,
// validation, GlobalExceptionHandler), not the services, repositories or the database. ProductService is replaced
// by a Mockito mock, and MockMvc sends HTTP requests to the controller without starting a real server.
// Expected JSON is written out by hand, so a change to the API's JSON shows up as a failing test.
@WebMvcTest(ProductController.class)
class ProductControllerWebMvcTest {
    private static final UUID PRODUCT_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final String VALID_REQUEST = """
            {
              "name": "Handcrafted Bamboo Basket",
              "price": 499.99,
              "description": "Woven by hand from local bamboo in Dangs, Gujarat",
              "imageUrl": "https://example.com/images/bamboo-basket.jpg",
              "category": "Traditional Crafts"
            }""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    private static Product product(UUID id, String name, String price, String categoryName) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        if (categoryName != null) {
            Category category = new Category();
            category.setName(categoryName);
            product.setCategory(category);
        }
        return product;
    }

    private static Product bambooBasket() {
        Product basket = product(PRODUCT_ID, "Handcrafted Bamboo Basket", "499.99", "Traditional Crafts");
        basket.setDescription("Woven by hand from local bamboo in Dangs, Gujarat");
        basket.setImageUrl("https://example.com/images/bamboo-basket.jpg");
        return basket;
    }

    @Nested
    @DisplayName("GET /products")
    class GetAllProducts {
        @Test
        void returnsEveryProductAsAJsonArray() throws Exception {
            // Arrange
            UUID ragiId = UUID.fromString("837a0c5a-5181-4a65-8e10-32759b931990");
            when(productService.getAllProducts()).thenReturn(List.of(
                    bambooBasket(),
                    product(ragiId, "Ragi Biscuits", "120.00", "Natural Foods")));

            // Act & Assert
            mockMvc.perform(get("/products"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].id").value(PRODUCT_ID.toString()))
                    .andExpect(jsonPath("$[0].name").value("Handcrafted Bamboo Basket"))
                    .andExpect(jsonPath("$[0].price").value(499.99))
                    .andExpect(jsonPath("$[0].category").value("Traditional Crafts"))
                    .andExpect(jsonPath("$[1].id").value(ragiId.toString()))
                    .andExpect(jsonPath("$[1].category").value("Natural Foods"));
        }

        @Test
        void withCategoryParameter_returnsOnlyThatCategory() throws Exception {
            // Arrange
            when(productService.getProductsByCategory("Natural Foods")).thenReturn(List.of(
                    product(UUID.randomUUID(), "Ragi Biscuits", "120.00", "Natural Foods")));

            // Act & Assert: the space in the parameter is URL-encoded on the wire, as a browser would send it
            mockMvc.perform(get("/products").param("category", "Natural Foods"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name").value("Ragi Biscuits"));
            verify(productService, never()).getAllProducts();
        }

        @Test
        void whenNoProductsExist_returnsAnEmptyArray() throws Exception {
            // Arrange
            when(productService.getAllProducts()).thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(get("/products"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]", JsonCompareMode.STRICT));
        }
    }

    @Nested
    @DisplayName("GET /products/{id}")
    class GetProductById {
        @Test
        void returnsTheWholeProduct() throws Exception {
            // Arrange
            when(productService.getProductById(PRODUCT_ID)).thenReturn(bambooBasket());

            // Act & Assert: STRICT means no field may be missing or extra
            mockMvc.perform(get("/products/{id}", PRODUCT_ID))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            {
                              "id": "150dac81-6130-4903-9385-9b682db879b3",
                              "name": "Handcrafted Bamboo Basket",
                              "price": 499.99,
                              "description": "Woven by hand from local bamboo in Dangs, Gujarat",
                              "imageUrl": "https://example.com/images/bamboo-basket.jpg",
                              "category": "Traditional Crafts"
                            }""", JsonCompareMode.STRICT));
        }

        @Test
        void unknownId_returns404WithTheErrorBody() throws Exception {
            // Arrange
            when(productService.getProductById(PRODUCT_ID))
                    .thenThrow(new ProductNotFoundException("Product with id " + PRODUCT_ID + " not found"));

            // Act & Assert
            mockMvc.perform(get("/products/{id}", PRODUCT_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Product with id " + PRODUCT_ID + " not found"))
                    .andExpect(jsonPath("$.path").value("/products/" + PRODUCT_ID))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        @Test
        void idThatIsNotAUuid_returns400WithoutCallingTheService() throws Exception {
            // Act & Assert
            mockMvc.perform(get("/products/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
                    .andExpect(jsonPath("$.message").value("Invalid value for 'id'"));
            verifyNoInteractions(productService);
        }
    }

    @Nested
    @DisplayName("POST /products")
    class CreateProduct {
        @Test
        void validBody_returns201WithTheProductAndItsLocation() throws Exception {
            // Arrange
            when(productService.createProduct("Handcrafted Bamboo Basket",
                    "Woven by hand from local bamboo in Dangs, Gujarat", new BigDecimal("499.99"),
                    "https://example.com/images/bamboo-basket.jpg", "Traditional Crafts"))
                    .thenReturn(bambooBasket());

            // Act & Assert
            mockMvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "http://localhost/products/" + PRODUCT_ID))
                    .andExpect(jsonPath("$.id").value(PRODUCT_ID.toString()))
                    .andExpect(jsonPath("$.name").value("Handcrafted Bamboo Basket"))
                    .andExpect(jsonPath("$.category").value("Traditional Crafts"));
        }

        @Test
        void bodyBreakingSeveralRules_returns400NamingEveryFieldAndSavesNothing() throws Exception {
            // Act & Assert: the field names are checked, not the rule texts, which depend on the machine's locale
            mockMvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "name": "  ", "price": -1 }"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message", containsString("name: ")))
                    .andExpect(jsonPath("$.message", containsString("price: ")));
            verifyNoInteractions(productService);
        }

        static Stream<Arguments> bodiesBreakingOneRule() {
            return Stream.of(
                    Arguments.of("price missing", "{ \"name\": \"Ragi Biscuits\" }", "price: "),
                    Arguments.of("price with 3 decimals", "{ \"name\": \"Ragi Biscuits\", \"price\": 10.555 }", "price: "),
                    Arguments.of("price over 8 digits", "{ \"name\": \"Ragi Biscuits\", \"price\": 123456789 }", "price: "),
                    Arguments.of("name over 255 characters",
                            "{ \"name\": \"" + "a".repeat(256) + "\", \"price\": 10 }", "name: "),
                    Arguments.of("description over 2000 characters",
                            "{ \"name\": \"Ragi Biscuits\", \"price\": 10, \"description\": \"" + "a".repeat(2001) + "\" }",
                            "description: "));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("bodiesBreakingOneRule")
        void bodyBreakingOneRule_returns400NamingThatField(String rule, String body, String field) throws Exception {
            // Act & Assert
            mockMvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message", containsString(field)));
            verifyNoInteractions(productService);
        }

        @Test
        void brokenJson_returns400MalformedRequest() throws Exception {
            // Act & Assert
            mockMvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content("{ \"name\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        }

        @Test
        void priceThatIsNotANumber_returns400MalformedRequest() throws Exception {
            // Act & Assert
            mockMvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"name\": \"Ragi Biscuits\", \"price\": \"abc\" }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        }

        @Test
        void missingBody_returns400MalformedRequest() throws Exception {
            // Act & Assert
            mockMvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        }
    }

    @Nested
    @DisplayName("PUT /products/{id}")
    class ReplaceProduct {
        @Test
        void validBody_returns200WithTheReplacedProduct() throws Exception {
            // Arrange
            when(productService.replaceProduct(PRODUCT_ID, "Handcrafted Bamboo Basket",
                    "Woven by hand from local bamboo in Dangs, Gujarat", new BigDecimal("499.99"),
                    "https://example.com/images/bamboo-basket.jpg", "Traditional Crafts"))
                    .thenReturn(bambooBasket());

            // Act & Assert
            mockMvc.perform(put("/products/{id}", PRODUCT_ID).contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(PRODUCT_ID.toString()))
                    .andExpect(jsonPath("$.price").value(499.99));
        }

        @Test
        void unknownId_returns404() throws Exception {
            // Arrange: PUT never creates a product at a client-chosen id
            when(productService.replaceProduct(eq(PRODUCT_ID), any(), any(), any(), any(), any()))
                    .thenThrow(new ProductNotFoundException("Product with id " + PRODUCT_ID + " not found"));

            // Act & Assert
            mockMvc.perform(put("/products/{id}", PRODUCT_ID).contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"));
        }

        @Test
        void invalidBody_returns400WithoutCallingTheService() throws Exception {
            // Act & Assert
            mockMvc.perform(put("/products/{id}", PRODUCT_ID).contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"name\": \"\", \"price\": 0 }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
            verifyNoInteractions(productService);
        }
    }

    @Nested
    @DisplayName("PATCH /products/{id}")
    class PatchProduct {
        private static final MediaType JSON_PATCH = MediaType.parseMediaType("application/json-patch+json");
        private static final String PRICE_PATCH = "[{ \"op\": \"replace\", \"path\": \"/price\", \"value\": 450 }]";

        @Test
        void jsonPatch_returns200WithThePatchedProduct() throws Exception {
            // Arrange: Spring builds the JsonPatch object from the request body, so the test cannot pass that same
            // object to when(); any(JsonPatch.class) matches whatever patch arrives
            Product patched = bambooBasket();
            patched.setPrice(new BigDecimal("450"));
            when(productService.patchProduct(eq(PRODUCT_ID), any(JsonPatch.class))).thenReturn(patched);

            // Act & Assert
            mockMvc.perform(patch("/products/{id}", PRODUCT_ID).contentType(JSON_PATCH).content(PRICE_PATCH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.price").value(450))
                    .andExpect(jsonPath("$.name").value("Handcrafted Bamboo Basket"));
        }

        @Test
        void plainJsonContentType_returns415WithoutCallingTheService() throws Exception {
            // Act & Assert: a JSON Patch must be sent as application/json-patch+json
            mockMvc.perform(patch("/products/{id}", PRODUCT_ID).contentType(MediaType.APPLICATION_JSON).content(PRICE_PATCH))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
            verifyNoInteractions(productService);
        }

        @Test
        void bodyThatIsNotAJsonPatch_returns400MalformedRequest() throws Exception {
            // Act & Assert: a JSON Patch is an array of operations, not an object
            mockMvc.perform(patch("/products/{id}", PRODUCT_ID).contentType(JSON_PATCH).content("{ \"price\": 450 }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
            verifyNoInteractions(productService);
        }

        @Test
        void patchThatCannotBeApplied_returns400InvalidPatch() throws Exception {
            // Arrange
            when(productService.patchProduct(eq(PRODUCT_ID), any(JsonPatch.class)))
                    .thenThrow(new JsonPatchException("no such path in target JSON document"));

            // Act & Assert
            mockMvc.perform(patch("/products/{id}", PRODUCT_ID).contentType(JSON_PATCH).content(PRICE_PATCH))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_PATCH"));
        }

        @Test
        void patchedProductBreakingARule_returns400NamingTheField() throws Exception {
            // Arrange: the real validator produces the violation the service would throw for a negative price
            ProductRequestDto negativePrice = new ProductRequestDto();
            negativePrice.setName("Handcrafted Bamboo Basket");
            negativePrice.setPrice(new BigDecimal("-5"));
            ConstraintViolationException violation = new ConstraintViolationException(
                    Validation.buildDefaultValidatorFactory().getValidator().validate(negativePrice));
            when(productService.patchProduct(eq(PRODUCT_ID), any(JsonPatch.class))).thenThrow(violation);

            // Act & Assert
            mockMvc.perform(patch("/products/{id}", PRODUCT_ID).contentType(JSON_PATCH).content(PRICE_PATCH))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message", containsString("price: ")));
        }
    }

    @Nested
    @DisplayName("DELETE /products/{id}")
    class DeleteProduct {
        @Test
        void existingProduct_returns204WithAnEmptyBody() throws Exception {
            // Act & Assert
            mockMvc.perform(delete("/products/{id}", PRODUCT_ID))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
            verify(productService).deleteProduct(PRODUCT_ID);
        }

        @Test
        void unknownId_returns404() throws Exception {
            // Arrange: deleteProduct returns void, so the exception is stubbed with doThrow(...).when(...)
            doThrow(new ProductNotFoundException("Product with id " + PRODUCT_ID + " not found"))
                    .when(productService).deleteProduct(PRODUCT_ID);

            // Act & Assert
            mockMvc.perform(delete("/products/{id}", PRODUCT_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("Errors outside the endpoints")
    class OtherErrors {
        @Test
        void unsupportedMethod_returns405WithTheAllowedMethods() throws Exception {
            // Act & Assert
            mockMvc.perform(put("/products"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().string("Allow", containsString("GET")))
                    .andExpect(header().string("Allow", containsString("POST")))
                    .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
        }

        @Test
        void unknownPath_returns404InTheSameErrorFormat() throws Exception {
            // Act & Assert
            mockMvc.perform(get("/no-such-path"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.path").value("/no-such-path"));
        }

        @Test
        void unexpectedFailure_returns500WithoutInternalDetails() throws Exception {
            // Arrange
            when(productService.getAllProducts())
                    .thenThrow(new IllegalStateException("Connection refused: db-host:3306 for user admin"));

            // Act & Assert: the client gets a generic message; the details are only in the server log
            mockMvc.perform(get("/products"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("Something went wrong on our side"))
                    .andExpect(content().string(not(containsString("db-host"))));
        }
    }
}
