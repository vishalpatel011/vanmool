package com.vanmool.orderservice.repositories;

import com.vanmool.orderservice.models.Order;
import com.vanmool.orderservice.models.OrderStatus;
import com.vanmool.orderservice.models.ShippingAddress;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The order queries against the MySQL in DB_URL (order_db, built by Flyway). @DataJpaTest starts only JPA and runs
// each test in a transaction that is rolled back at the end, so nothing stays in the database.
// replace = NONE: use the real MySQL, not an in-memory database, because the unique key and DECIMAL columns matter.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryIT {
    private final UUID userId = UUID.randomUUID();

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    private Order newOrder(UUID userId, String idempotencyKey, int baskets) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(idempotencyKey);
        order.setShippingAddress(new ShippingAddress("Asha Gamit", "9876543210", "12 Station Road", null, "Ahwa",
                "Gujarat", "394710"));
        order.addItem(UUID.randomUUID(), "Handcrafted Bamboo Basket", new BigDecimal("499.99"), baskets);
        order.addItem(UUID.randomUUID(), "Ragi Biscuits", new BigDecimal("120.00"), 3);
        order.setTotalAmount(order.calculateTotal());
        return order;
    }

    // Writes everything to MySQL and empties the persistence context, so the next read really queries the database
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void savedOrder_isReadBackWithItsItemsInTheSameQuery() {
        // Arrange
        Order order = orderRepository.save(newOrder(userId, null, 2));
        flushAndClear();

        // Act
        Order found = orderRepository.findByIdAndUserId(order.getId(), userId).orElseThrow();

        // Assert: the entity graph fetched the items with the order; amounts come back exactly
        assertTrue(Hibernate.isInitialized(found.getItems()));
        assertEquals(2, found.getItems().size());
        assertEquals(new BigDecimal("1359.98"), found.getTotalAmount());
        assertEquals(0L, found.getVersion());
    }

    @Test
    void anotherUsersId_findsNothing() {
        // Arrange
        Order order = orderRepository.save(newOrder(userId, null, 1));
        flushAndClear();

        // Act & Assert
        assertTrue(orderRepository.findByIdAndUserId(order.getId(), UUID.randomUUID()).isEmpty());
    }

    @Test
    void sameIdempotencyKeyForTheSameUser_cannotBeSavedTwice() {
        // Arrange
        orderRepository.saveAndFlush(newOrder(userId, "checkout-1", 1));

        // Act & Assert: the unique key (user_id, idempotency_key) refuses the second order
        assertThrows(DataIntegrityViolationException.class,
                () -> orderRepository.saveAndFlush(newOrder(userId, "checkout-1", 1)));
    }

    @Test
    void sameKeyForAnotherUser_andOrdersWithoutAKey_areAllowed() {
        // Act: MySQL allows any number of NULLs in a unique key
        orderRepository.saveAndFlush(newOrder(userId, "checkout-1", 1));
        orderRepository.saveAndFlush(newOrder(UUID.randomUUID(), "checkout-1", 1));
        orderRepository.saveAndFlush(newOrder(userId, null, 1));
        orderRepository.saveAndFlush(newOrder(userId, null, 1));
        flushAndClear();

        // Assert
        assertEquals("checkout-1", orderRepository.findByUserIdAndIdempotencyKey(userId, "checkout-1").orElseThrow().getIdempotencyKey());
        assertEquals(3, orderRepository.findByUserId(userId, PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void historyPage_loadsTheItemsOfAllItsOrdersInOneBatch() {
        // Arrange
        for (int i = 1; i <= 3; i++) {
            orderRepository.save(newOrder(userId, null, i));
        }
        flushAndClear();

        // Act: the page query loads the orders only
        Page<Order> page = orderRepository.findByUserId(userId,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        List<Order> orders = page.getContent();
        assertFalse(Hibernate.isInitialized(orders.get(1).getItems()));
        orders.get(0).getItems().size();

        // Assert: touching the first order's items loaded the items of every order on the page (@BatchSize)
        assertTrue(Hibernate.isInitialized(orders.get(1).getItems()));
        assertTrue(Hibernate.isInitialized(orders.get(2).getItems()));
        assertEquals(3, page.getTotalElements());
    }

    @Test
    void moveStatus_movesOnlyFromTheExpectedStatus_andCountsTheVersionUp() {
        // Arrange
        Order order = orderRepository.saveAndFlush(newOrder(userId, null, 1));

        // Act
        int first = orderRepository.moveStatus(order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED);
        int second = orderRepository.moveStatus(order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED);
        entityManager.clear();

        // Assert: the second change found the order no longer pending, and changed nothing
        assertEquals(1, first);
        assertEquals(0, second);
        Order found = orderRepository.findByIdAndUserId(order.getId(), userId).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, found.getStatus());
        assertEquals(1L, found.getVersion());
    }
}
