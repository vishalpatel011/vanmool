package com.vanmool.orderservice.repositories;

import com.vanmool.orderservice.models.Order;
import com.vanmool.orderservice.models.OrderStatus;
import com.vanmool.orderservice.models.OrderStatusChange;
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
import java.time.Instant;
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
    private OrderStatusChangeRepository statusChangeRepository;

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
    void unpaidOrdersBeforeACutoff_areFoundOldestFirst() throws Exception {
        // Arrange: two pending orders, and one confirmed that must not be found
        Order older = orderRepository.saveAndFlush(newOrder(userId, null, 1));
        Thread.sleep(5);
        Order newer = orderRepository.saveAndFlush(newOrder(userId, null, 1));
        Order confirmed = orderRepository.saveAndFlush(newOrder(userId, null, 1));
        orderRepository.moveStatus(confirmed.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED);
        flushAndClear();

        // Act
        List<UUID> found = orderRepository.findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                OrderStatus.PENDING_PAYMENT, java.time.Instant.now().plusSeconds(1)).stream()
                .filter(order -> order.getUserId().equals(userId)).map(Order::getId).toList();

        // Assert
        assertEquals(List.of(older.getId(), newer.getId()), found);
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

    @Test
    void ship_movesOnlyAConfirmedOrder_andStoresHowItWasSent() {
        // Arrange: one paid order, one still waiting for payment
        Order paid = orderRepository.saveAndFlush(newOrder(userId, null, 1));
        orderRepository.moveStatus(paid.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED);
        Order unpaid = orderRepository.saveAndFlush(newOrder(userId, null, 1));

        // Act
        int shipped = orderRepository.ship(paid.getId(), "India Post", "EE123456789IN");
        int again = orderRepository.ship(paid.getId(), "Delhivery", "DL1");
        int notPaid = orderRepository.ship(unpaid.getId(), "India Post", "EE1");
        entityManager.clear();

        // Assert: the second UPDATE found the order already SHIPPED and kept the first shipment
        assertEquals(List.of(1, 0, 0), List.of(shipped, again, notPaid));
        Order found = orderRepository.findWithItemsById(paid.getId()).orElseThrow();
        assertEquals(OrderStatus.SHIPPED, found.getStatus());
        assertEquals("India Post", found.getCarrier());
        assertEquals("EE123456789IN", found.getTrackingNumber());
        assertEquals(2L, found.getVersion());
        assertTrue(Hibernate.isInitialized(found.getItems()));
        assertEquals(OrderStatus.PENDING_PAYMENT, orderRepository.findWithItemsById(unpaid.getId()).orElseThrow().getStatus());
    }

    @Test
    void ordersOfOneStatus_areFoundWithoutTheOthers() {
        // Arrange
        Order paid = orderRepository.saveAndFlush(newOrder(userId, null, 1));
        orderRepository.moveStatus(paid.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED);
        orderRepository.saveAndFlush(newOrder(userId, null, 1));
        entityManager.clear();

        // Act: other tests' orders may be in the table too; only this test's user is looked at
        List<UUID> confirmed = orderRepository.findByStatus(OrderStatus.CONFIRMED, PageRequest.of(0, 50, Sort.by("createdAt")))
                .stream().filter(order -> order.getUserId().equals(userId)).map(Order::getId).toList();

        // Assert
        assertEquals(List.of(paid.getId()), confirmed);
    }

    @Test
    void timeline_isReadOldestFirst_forOneOrder_andForAPageOfOrders() {
        // Arrange: rows saved out of order, for two orders
        Order first = orderRepository.saveAndFlush(newOrder(userId, null, 1));
        Order second = orderRepository.saveAndFlush(newOrder(userId, null, 1));
        Instant placed = Instant.parse("2026-10-26T10:00:00Z");
        statusChangeRepository.save(OrderStatusChange.of(first.getId(), OrderStatus.SHIPPED, placed.plusSeconds(7200), "India Post"));
        statusChangeRepository.save(OrderStatusChange.of(first.getId(), OrderStatus.PENDING_PAYMENT, placed, null));
        statusChangeRepository.save(OrderStatusChange.of(first.getId(), OrderStatus.CONFIRMED, placed.plusSeconds(60), "Paid"));
        statusChangeRepository.save(OrderStatusChange.of(second.getId(), OrderStatus.PENDING_PAYMENT, placed.plusSeconds(30), null));
        flushAndClear();

        // Act
        List<OrderStatusChange> one = statusChangeRepository.findByOrderIdOrderByChangedAtAsc(first.getId());
        List<OrderStatusChange> both = statusChangeRepository.findByOrderIdInOrderByChangedAtAsc(
                List.of(first.getId(), second.getId()));

        // Assert: DATETIME(6) keeps the times exactly
        assertEquals(List.of(OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED, OrderStatus.SHIPPED),
                one.stream().map(OrderStatusChange::getStatus).toList());
        assertEquals(placed.plusSeconds(7200), one.get(2).getChangedAt());
        assertEquals(4, both.size());
        assertEquals(second.getId(), both.get(1).getOrderId());
    }
}
