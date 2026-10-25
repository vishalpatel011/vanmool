package com.vanmool.cartservice.controllers;

import com.vanmool.cartservice.configs.SecurityConfig;
import com.vanmool.cartservice.dtos.CartItemResponseDto;
import com.vanmool.cartservice.dtos.CartResponseDto;
import com.vanmool.cartservice.exceptions.CartLimitExceededException;
import com.vanmool.cartservice.exceptions.ProductNotFoundException;
import com.vanmool.cartservice.exceptions.ProductServiceUnavailableException;
import com.vanmool.cartservice.security.RestSecurityErrorHandler;
import com.vanmool.cartservice.services.CartService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Web layer test with the real security rules and error format. jwt() from spring-security-test stands in for a
// checked bearer token with a chosen subject; the tests without a token, or with a broken one, go through the real
// JWT checking. CartService is a mock.
@WebMvcTest(CartController.class)
@Import({SecurityConfig.class, RestSecurityErrorHandler.class})
class CartControllerWebMvcTest {
    private static final UUID USER_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final CartResponseDto CART_WITH_TWO_BASKETS = new CartResponseDto(List.of(
            new CartItemResponseDto(BASKET_ID, "Handcrafted Bamboo Basket", "https://example.com/images/bamboo-basket.jpg",
                    new BigDecimal("499.99"), 2, new BigDecimal("999.98"), true)),
            2, new BigDecimal("999.98"));
    private static final CartResponseDto EMPTY_CART = new CartResponseDto(List.of(), 0, BigDecimal.ZERO);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    private static RequestPostProcessor userToken() {
        return jwt().jwt(token -> token.subject(USER_ID.toString()));
    }

    @Nested
    @DisplayName("Authentication")
    class Authentication {
        @Test
        void noToken_returns401InTheErrorFormat() throws Exception {
            mockMvc.perform(get("/cart"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"))
                    .andExpect(jsonPath("$.path").value("/cart"));
            verifyNoInteractions(cartService);
        }

        @Test
        void tokenThatIsNotAJwt_returns401() throws Exception {
            // A malformed token is refused before any key is needed
            mockMvc.perform(get("/cart").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            verifyNoInteractions(cartService);
        }

        @Test
        void tokenWhoseSubjectIsNotAUserId_returns403() throws Exception {
            mockMvc.perform(get("/cart").with(jwt().jwt(token -> token.subject("some-service"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("This token does not belong to a user"));
            verifyNoInteractions(cartService);
        }
    }

    @Nested
    @DisplayName("GET /cart")
    class GetCart {
        @Test
        void returnsTheCartOfTheUserNamedByTheToken() throws Exception {
            // Arrange
            when(cartService.getCart(USER_ID)).thenReturn(CART_WITH_TWO_BASKETS);

            // Act & Assert: STRICT means no field may be missing or extra
            mockMvc.perform(get("/cart").with(userToken()))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            {
                              "items": [
                                {
                                  "productId": "150dac81-6130-4903-9385-9b682db879b3",
                                  "name": "Handcrafted Bamboo Basket",
                                  "imageUrl": "https://example.com/images/bamboo-basket.jpg",
                                  "unitPrice": 499.99,
                                  "quantity": 2,
                                  "lineTotal": 999.98,
                                  "available": true
                                }
                              ],
                              "totalQuantity": 2,
                              "totalPrice": 999.98
                            }""", JsonCompareMode.STRICT));
        }

        @Test
        void productServiceDown_returns503() throws Exception {
            // Arrange
            when(cartService.getCart(USER_ID)).thenThrow(
                    new ProductServiceUnavailableException("The product catalog is not available right now", null));

            // Act & Assert
            mockMvc.perform(get("/cart").with(userToken()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("PRODUCT_SERVICE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("The product catalog is not available right now"));
        }

        @Test
        void redisDown_returns503WithoutInternalDetails() throws Exception {
            // Arrange
            when(cartService.getCart(USER_ID)).thenThrow(
                    new RedisConnectionFailureException("Unable to connect to Redis at localhost:6379"));

            // Act & Assert
            mockMvc.perform(get("/cart").with(userToken()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("CART_UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("Carts are not available right now"));
        }
    }

    @Nested
    @DisplayName("POST /cart/items")
    class AddItem {
        @Test
        void validRequest_addsTheProductAndReturnsTheCart() throws Exception {
            // Arrange
            when(cartService.addItem(USER_ID, BASKET_ID, 2)).thenReturn(CART_WITH_TWO_BASKETS);

            // Act & Assert
            mockMvc.perform(post("/cart/items").with(userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\": \"150dac81-6130-4903-9385-9b682db879b3\", \"quantity\": 2}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalQuantity").value(2))
                    .andExpect(jsonPath("$.items[0].productId").value(BASKET_ID.toString()));
            verify(cartService).addItem(USER_ID, BASKET_ID, 2);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, 21})
        void quantityOutsideOneToTwenty_returns400WithoutCallingTheService(int quantity) throws Exception {
            mockMvc.perform(post("/cart/items").with(userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\": \"150dac81-6130-4903-9385-9b682db879b3\", \"quantity\": " + quantity + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
            verifyNoInteractions(cartService);
        }

        @Test
        void missingFields_returns400NamingEachField() throws Exception {
            mockMvc.perform(post("/cart/items").with(userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("productId: must not be null; quantity: must not be null"));
            verifyNoInteractions(cartService);
        }

        @Test
        void productIdThatIsNotAUuid_returns400() throws Exception {
            mockMvc.perform(post("/cart/items").with(userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\": \"basket\", \"quantity\": 1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
            verifyNoInteractions(cartService);
        }

        @Test
        void unknownProduct_returns404() throws Exception {
            // Arrange
            when(cartService.addItem(USER_ID, BASKET_ID, 1)).thenThrow(new ProductNotFoundException(BASKET_ID));

            // Act & Assert
            mockMvc.perform(post("/cart/items").with(userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\": \"150dac81-6130-4903-9385-9b682db879b3\", \"quantity\": 1}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Product with id " + BASKET_ID + " not found"));
        }

        @Test
        void overTheCartLimit_returns409() throws Exception {
            // Arrange
            when(cartService.addItem(USER_ID, BASKET_ID, 5)).thenThrow(
                    new CartLimitExceededException("A cart can hold at most 20 of one product; it already holds 19"));

            // Act & Assert
            mockMvc.perform(post("/cart/items").with(userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\": \"150dac81-6130-4903-9385-9b682db879b3\", \"quantity\": 5}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CART_LIMIT_EXCEEDED"));
        }
    }

    @Nested
    @DisplayName("PUT /cart/items/{productId}")
    class SetItemQuantity {
        @Test
        void validRequest_setsTheQuantity() throws Exception {
            // Arrange
            when(cartService.setItemQuantity(USER_ID, BASKET_ID, 2)).thenReturn(CART_WITH_TWO_BASKETS);

            // Act & Assert
            mockMvc.perform(put("/cart/items/{productId}", BASKET_ID).with(userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\": 2}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].quantity").value(2));
        }

        @Test
        void quantityZero_returns400_becauseRemovingIsADelete() throws Exception {
            mockMvc.perform(put("/cart/items/{productId}", BASKET_ID).with(userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\": 0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("quantity: must be greater than or equal to 1"));
            verifyNoInteractions(cartService);
        }

        @Test
        void productIdThatIsNotAUuid_returns400() throws Exception {
            mockMvc.perform(put("/cart/items/not-a-uuid").with(userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\": 2}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
                    .andExpect(jsonPath("$.message").value("Invalid value for 'productId'"));
            verifyNoInteractions(cartService);
        }
    }

    @Nested
    @DisplayName("DELETE /cart/items/{productId}, DELETE /cart and other errors")
    class Remove {
        @Test
        void removeItem_returns204WithoutABody() throws Exception {
            mockMvc.perform(delete("/cart/items/{productId}", BASKET_ID).with(userToken()))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
            verify(cartService).removeItem(USER_ID, BASKET_ID);
        }

        @Test
        void emptyCart_isReturnedWithZeroTotals() throws Exception {
            // Arrange
            when(cartService.getCart(USER_ID)).thenReturn(EMPTY_CART);

            // Act & Assert
            mockMvc.perform(get("/cart").with(userToken()))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"items\": [], \"totalQuantity\": 0, \"totalPrice\": 0}", JsonCompareMode.STRICT));
        }

        @Test
        void wrongMethod_returns405InTheErrorFormat() throws Exception {
            mockMvc.perform(patch("/cart").with(userToken()))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"))
                    .andExpect(header().exists(HttpHeaders.ALLOW));
        }

        @Test
        void unexpectedError_returns500WithoutInternalDetails() throws Exception {
            // Arrange
            doThrow(new IllegalStateException("secret internal detail")).when(cartService).clearCart(USER_ID);

            // Act & Assert
            mockMvc.perform(delete("/cart").with(userToken()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("Something went wrong on our side"));
        }

        @Test
        void clearCart_returns204WithoutABody() throws Exception {
            mockMvc.perform(delete("/cart").with(userToken()))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
            verify(cartService).clearCart(USER_ID);
        }
    }
}
