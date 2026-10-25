package com.vanmool.productservice;

import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.services.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// The product service with Redis unreachable (a port nothing listens on), against the MySQL in DB_URL: every cache
// read and eviction fails, CacheConfig's error handler logs it, and the API keeps working from MySQL.
@SpringBootTest(properties = "spring.data.redis.port=6390")
class ProductCacheWithoutRedisIT {
    private final String category = "it-no-redis-" + UUID.randomUUID();

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void removeProducts() {
        jdbcTemplate.update("DELETE p FROM product p JOIN category c ON c.id = p.category_id WHERE c.name = ?", category);
        jdbcTemplate.update("DELETE FROM category WHERE name = ?", category);
    }

    @Test
    void readsAndChanges_workFromMySql() throws ProductNotFoundException {
        // Arrange
        ProductRequestDto request = new ProductRequestDto();
        request.setName("Ragi Biscuits");
        request.setPrice(new BigDecimal("120.00"));
        request.setCategory(category);
        UUID id = productService.createProduct(request).getId();

        // Act & Assert: read, change (its eviction fails too) and read the change, then delete
        assertEquals("Ragi Biscuits", productService.getProductById(id).getName());
        request.setName("Ragi Biscuits, 500 g");
        productService.replaceProduct(id, request);
        assertEquals("Ragi Biscuits, 500 g", productService.getProductById(id).getName());
        productService.deleteProduct(id);
        assertThrows(ProductNotFoundException.class, () -> productService.getProductById(id));
    }
}
