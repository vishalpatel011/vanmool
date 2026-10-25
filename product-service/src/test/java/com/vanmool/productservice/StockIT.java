package com.vanmool.productservice;

import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.dtos.ReservationItemRequestDto;
import com.vanmool.productservice.exceptions.InsufficientStockException;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.services.ProductService;
import com.vanmool.productservice.services.StockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Stock and reservations against the MySQL in DB_URL: the native upsert, the all-or-nothing rollback, releasing twice,
// and no overselling when many orders take the last units at the same moment. Uses its own products in a category
// with a unique name and removes everything afterwards.
@SpringBootTest
class StockIT {
    private final String category = "it-stock-" + UUID.randomUUID();
    private final List<UUID> orderIds = new ArrayList<>();
    private UUID basketId;
    private UUID biscuitsId;

    @Autowired
    private ProductService productService;

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID createProduct(String name, String price) {
        ProductRequestDto request = new ProductRequestDto();
        request.setName(name);
        request.setPrice(new BigDecimal(price));
        request.setCategory(category);
        return productService.createProduct(request).getId();
    }

    private static ReservationItemRequestDto item(UUID productId, int quantity) {
        ReservationItemRequestDto item = new ReservationItemRequestDto();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    private UUID newOrderId() {
        UUID orderId = UUID.randomUUID();
        orderIds.add(orderId);
        return orderId;
    }

    private int stockOf(UUID productId) throws ProductNotFoundException {
        return stockService.getStock(productId).quantity();
    }

    @BeforeEach
    void createProducts() throws ProductNotFoundException {
        basketId = createProduct("Bamboo Basket", "450.00");
        biscuitsId = createProduct("Ragi Biscuits", "120.00");
        stockService.adjustStock(basketId, 5);
        stockService.adjustStock(biscuitsId, 2);
    }

    @AfterEach
    void removeEverything() {
        for (UUID orderId : orderIds) {
            jdbcTemplate.update("DELETE FROM stock_reservation_item WHERE order_id = UUID_TO_BIN(?)", orderId.toString());
            jdbcTemplate.update("DELETE FROM stock_reservation WHERE order_id = UUID_TO_BIN(?)", orderId.toString());
        }
        jdbcTemplate.update("DELETE s FROM stock s JOIN product p ON p.id = s.product_id JOIN category c ON c.id = p.category_id WHERE c.name = ?", category);
        jdbcTemplate.update("DELETE p FROM product p JOIN category c ON c.id = p.category_id WHERE c.name = ?", category);
        jdbcTemplate.update("DELETE FROM category WHERE name = ?", category);
    }

    @Test
    void adjustments_createTheStockRowAndAddOrRemoveUnits() throws ProductNotFoundException {
        // The first adjustment inserted the row (native INSERT ... ON DUPLICATE KEY UPDATE), the next ones change it
        stockService.adjustStock(basketId, 10);
        stockService.adjustStock(basketId, -3);

        assertEquals(12, stockOf(basketId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock WHERE product_id = UUID_TO_BIN(?)", Integer.class, basketId.toString()));
        assertThrows(InsufficientStockException.class, () -> stockService.adjustStock(basketId, -13));
        assertEquals(12, stockOf(basketId));
    }

    @Test
    void reserve_takesTheUnits_andStoresNamesAndPrices() throws ProductNotFoundException {
        // Act
        StockService.ReservationResult result = stockService.reserve(newOrderId(), List.of(item(basketId, 2), item(biscuitsId, 1)));

        // Assert
        assertTrue(result.created());
        assertEquals(3, stockOf(basketId));
        assertEquals(1, stockOf(biscuitsId));
        assertEquals(List.of(new BigDecimal("450.00"), new BigDecimal("120.00")),
                result.reservation().items().stream()
                        .sorted((a, b) -> a.name().compareTo(b.name()))
                        .map(reservedItem -> reservedItem.unitPrice()).toList());
    }

    @Test
    void oneProductShort_rollsBackTheUnitsTakenFromTheOthers() {
        // Act: 2 baskets would be fine, but only 2 biscuits exist, not 3
        UUID orderId = newOrderId();
        assertThrows(InsufficientStockException.class,
                () -> stockService.reserve(orderId, List.of(item(basketId, 2), item(biscuitsId, 3))));

        // Assert: the baskets are all still there, and no reservation was stored
        assertEquals(5, jdbcTemplate.queryForObject(
                "SELECT quantity FROM stock WHERE product_id = UUID_TO_BIN(?)", Integer.class, basketId.toString()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_reservation WHERE order_id = UUID_TO_BIN(?)", Integer.class, orderId.toString()));
    }

    @Test
    void releasingTwice_putsTheUnitsBackOnce() throws ProductNotFoundException {
        // Arrange
        UUID orderId = newOrderId();
        stockService.reserve(orderId, List.of(item(basketId, 4)));

        // Act
        stockService.release(orderId);
        stockService.release(orderId);

        // Assert
        assertEquals(5, stockOf(basketId));
        assertEquals("RELEASED", jdbcTemplate.queryForObject(
                "SELECT status FROM stock_reservation WHERE order_id = UUID_TO_BIN(?)", String.class, orderId.toString()));
    }

    @Test
    void reservingTwiceForOneOrder_takesTheUnitsOnce() throws ProductNotFoundException {
        // Arrange
        UUID orderId = newOrderId();

        // Act
        StockService.ReservationResult first = stockService.reserve(orderId, List.of(item(basketId, 2)));
        StockService.ReservationResult second = stockService.reserve(orderId, List.of(item(basketId, 2)));

        // Assert
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(3, stockOf(basketId));
    }

    @Test
    void manyOrdersAtOnce_neverTakeMoreUnitsThanThereAre() throws Exception {
        // Arrange: 5 baskets, 12 orders of 1 basket each, released together
        int orders = 12;
        ExecutorService pool = Executors.newFixedThreadPool(orders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            UUID orderId = newOrderId();
            Callable<Boolean> order = () -> {
                start.await();
                try {
                    stockService.reserve(orderId, List.of(item(basketId, 1)));
                    return true;
                } catch (InsufficientStockException e) {
                    return false;
                }
            };
            results.add(pool.submit(order));
        }

        // Act
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                succeeded++;
            }
        }
        pool.shutdown();

        // Assert: exactly 5 orders got a basket, and the count never went below zero
        assertEquals(5, succeeded);
        assertEquals(0, stockOf(basketId));
    }
}
