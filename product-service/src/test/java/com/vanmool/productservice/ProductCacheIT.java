package com.vanmool.productservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.dtos.ProductResponseDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.services.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Integration test of the product cache against the Redis started by docker compose and the MySQL in DB_URL:
// the key and JSON format, the TTL, cache hits, and evictions that wait for the transaction to commit.
@SpringBootTest
class ProductCacheIT {
    private final String category = "it-cache-" + UUID.randomUUID();
    private UUID productId;
    private String key;

    @Autowired
    private ProductService productService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private ProductRequestDto request(String name, String price) {
        ProductRequestDto request = new ProductRequestDto();
        request.setName(name);
        request.setPrice(new BigDecimal(price));
        request.setOriginRegion("Dangs, Gujarat");
        request.setCategory(category);
        return request;
    }

    @BeforeEach
    void createProduct() {
        productId = productService.createProduct(request("Bamboo Basket", "450.00")).getId();
        key = "product:" + productId;
    }

    @AfterEach
    void removeProduct() {
        redis.delete(key);
        jdbcTemplate.update("DELETE p FROM product p JOIN category c ON c.id = p.category_id WHERE c.name = ?", category);
        jdbcTemplate.update("DELETE FROM category WHERE name = ?", category);
    }

    @Test
    void firstRead_storesTheResponseAsJsonUnderProductId_withATimeToLive() throws Exception {
        // Act
        productService.getProductById(productId);

        // Assert: the value is the JSON that GET /products/{id} returns, and it expires within the configured 10 minutes
        String json = redis.opsForValue().get(key);
        assertNotNull(json);
        assertTrue(json.contains("\"price\":450.00"), json);
        ProductResponseDto cached = objectMapper.readValue(json, ProductResponseDto.class);
        assertEquals(productId, cached.getId());
        assertEquals("Bamboo Basket", cached.getName());
        assertEquals(new BigDecimal("450.00"), cached.getPrice());
        assertEquals(category, cached.getCategory());
        assertEquals("Dangs, Gujarat", cached.getOriginRegion());
        Long secondsLeft = redis.getExpire(key, TimeUnit.SECONDS);
        assertTrue(secondsLeft > 0 && secondsLeft <= 600, "TTL was " + secondsLeft);
    }

    @Test
    void laterReads_comeFromRedis_notFromMySql() throws ProductNotFoundException {
        // Arrange: cache the product, then change its row behind the service's back
        productService.getProductById(productId);
        jdbcTemplate.update("UPDATE product SET name = ? WHERE id = UUID_TO_BIN(?)", "Changed in MySQL", productId.toString());

        // Act & Assert: the cached name is returned, so MySQL was not read (this is also why the TTL exists)
        assertEquals("Bamboo Basket", productService.getProductById(productId).getName());
    }

    @Test
    void replace_removesTheEntry_soTheNextReadSeesTheChange() throws ProductNotFoundException {
        // Arrange
        productService.getProductById(productId);

        // Act
        productService.replaceProduct(productId, request("Bamboo Basket, large", "549.00"));

        // Assert
        assertFalse(redis.hasKey(key));
        assertEquals("Bamboo Basket, large", productService.getProductById(productId).getName());
    }

    @Test
    void delete_removesTheEntry_soTheProductIsNotFound() throws ProductNotFoundException {
        // Arrange
        productService.getProductById(productId);

        // Act
        productService.deleteProduct(productId);

        // Assert
        assertFalse(redis.hasKey(key));
        assertThrows(ProductNotFoundException.class, () -> productService.getProductById(productId));
    }

    @Test
    void insideATransaction_theEntryIsRemovedOnlyWhenTheTransactionCommits() throws ProductNotFoundException {
        // Arrange
        productService.getProductById(productId);

        // Act: the replace joins this outer transaction
        transactionTemplate.executeWithoutResult(status -> {
            try {
                productService.replaceProduct(productId, request("Bamboo Basket, large", "549.00"));
            } catch (ProductNotFoundException e) {
                throw new IllegalStateException(e);
            }
            // Not committed yet: MySQL still holds the old product for everyone else, and so does the cache
            assertTrue(redis.hasKey(key));
        });

        // Assert
        assertFalse(redis.hasKey(key));
    }

    @Test
    void aRolledBackChange_keepsTheEntry() throws ProductNotFoundException {
        // Arrange
        productService.getProductById(productId);

        // Act: the change is rolled back, so MySQL keeps the old product
        transactionTemplate.executeWithoutResult(status -> {
            try {
                productService.replaceProduct(productId, request("Bamboo Basket, large", "549.00"));
            } catch (ProductNotFoundException e) {
                throw new IllegalStateException(e);
            }
            status.setRollbackOnly();
        });

        // Assert: the cached entry still matches MySQL, so it was right to keep it
        assertTrue(redis.hasKey(key));
        assertEquals("Bamboo Basket", productService.getProductById(productId).getName());
        assertEquals("Bamboo Basket", jdbcTemplate.queryForObject(
                "SELECT name FROM product WHERE id = UUID_TO_BIN(?)", String.class, productId.toString()));
    }
}
