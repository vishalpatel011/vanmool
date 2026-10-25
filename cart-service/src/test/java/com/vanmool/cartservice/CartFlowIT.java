package com.vanmool.cartservice;

import com.vanmool.cartservice.clients.ProductClient;
import com.vanmool.cartservice.clients.ProductDetails;
import com.vanmool.cartservice.exceptions.ProductNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The whole service, from HTTP request to Redis and back, against the Redis started by docker compose. Only
// product-service is replaced (ProductClient is a mock), so the test does not depend on another running service.
@SpringBootTest
@AutoConfigureMockMvc
class CartFlowIT {
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final UUID BISCUITS_ID = UUID.fromString("837a0c5a-5181-4a65-8e10-32759b931990");

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoBean
    private ProductClient productClient;

    private static RequestPostProcessor tokenFor(UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()));
    }

    private static String addRequest(UUID productId, int quantity) {
        return "{\"productId\": \"" + productId + "\", \"quantity\": " + quantity + "}";
    }

    @BeforeEach
    void catalog() {
        when(productClient.getProduct(BASKET_ID)).thenReturn(
                new ProductDetails(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), null));
        when(productClient.getProduct(BISCUITS_ID)).thenReturn(
                new ProductDetails(BISCUITS_ID, "Ragi Biscuits", new BigDecimal("120.00"), null));
    }

    @AfterEach
    void deleteCarts() {
        redis.delete("cart:" + userId);
        redis.delete("cart:" + otherUserId);
    }

    @Test
    void addChangeRemoveAndClear() throws Exception {
        // Add 1 basket, then 1 more, then 3 packets of biscuits
        mockMvc.perform(post("/cart/items").with(tokenFor(userId))
                .contentType(MediaType.APPLICATION_JSON).content(addRequest(BASKET_ID, 1))).andExpect(status().isOk());
        mockMvc.perform(post("/cart/items").with(tokenFor(userId))
                .contentType(MediaType.APPLICATION_JSON).content(addRequest(BASKET_ID, 1))).andExpect(status().isOk());
        mockMvc.perform(post("/cart/items").with(tokenFor(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(addRequest(BISCUITS_ID, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalQuantity").value(5))
                .andExpect(jsonPath("$.totalPrice").value(1359.98));
        assertEquals(Map.of(BASKET_ID.toString(), "2", BISCUITS_ID.toString(), "3"),
                redis.opsForHash().entries("cart:" + userId));

        // Set the biscuits to 1
        mockMvc.perform(put("/cart/items/{productId}", BISCUITS_ID).with(tokenFor(userId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPrice").value(1119.98));

        // Remove the baskets
        mockMvc.perform(delete("/cart/items/{productId}", BASKET_ID).with(tokenFor(userId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/cart").with(tokenFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("Ragi Biscuits"));

        // Clear the cart: the key is gone
        mockMvc.perform(delete("/cart").with(tokenFor(userId))).andExpect(status().isNoContent());
        assertFalse(redis.hasKey("cart:" + userId));
        mockMvc.perform(get("/cart").with(tokenFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalPrice").value(0));
    }

    @Test
    void eachUserSeesOnlyTheirOwnCart() throws Exception {
        // Arrange
        mockMvc.perform(post("/cart/items").with(tokenFor(userId))
                .contentType(MediaType.APPLICATION_JSON).content(addRequest(BASKET_ID, 2))).andExpect(status().isOk());

        // Act & Assert
        mockMvc.perform(get("/cart").with(tokenFor(otherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void goingOverTheLimit_returns409_andKeepsTheQuantityThatWasThere() throws Exception {
        // Arrange
        mockMvc.perform(post("/cart/items").with(tokenFor(userId))
                .contentType(MediaType.APPLICATION_JSON).content(addRequest(BASKET_ID, 19))).andExpect(status().isOk());

        // Act
        mockMvc.perform(post("/cart/items").with(tokenFor(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(addRequest(BASKET_ID, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CART_LIMIT_EXCEEDED"));

        // Assert
        assertEquals("19", redis.opsForHash().get("cart:" + userId, BASKET_ID.toString()));
    }

    @Test
    void productRemovedFromTheCatalogLater_staysInTheCartAsUnavailable() throws Exception {
        // Arrange: added while it existed, then removed from the catalog
        mockMvc.perform(post("/cart/items").with(tokenFor(userId))
                .contentType(MediaType.APPLICATION_JSON).content(addRequest(BASKET_ID, 1))).andExpect(status().isOk());
        when(productClient.getProduct(BASKET_ID)).thenThrow(new ProductNotFoundException(BASKET_ID));

        // Act & Assert
        mockMvc.perform(get("/cart").with(tokenFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].available").value(false))
                .andExpect(jsonPath("$.totalQuantity").value(0));
    }
}
