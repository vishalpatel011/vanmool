package com.vanmool.orderservice.services;

import com.vanmool.orderservice.clients.CartClient;
import com.vanmool.orderservice.clients.ProductClient;
import com.vanmool.orderservice.dtos.OrderResponseDto;
import com.vanmool.orderservice.dtos.ShippingAddressDto;
import com.vanmool.orderservice.exceptions.CartEmptyException;
import com.vanmool.orderservice.exceptions.CartServiceUnavailableException;
import com.vanmool.orderservice.exceptions.InsufficientStockException;
import com.vanmool.orderservice.exceptions.InvalidOrderStateException;
import com.vanmool.orderservice.exceptions.OrderNotFoundException;
import com.vanmool.orderservice.exceptions.ProductServiceUnavailableException;
import com.vanmool.orderservice.events.OrderCancelledEvent;
import com.vanmool.orderservice.events.OrderPlacedEvent;
import com.vanmool.orderservice.events.OutboxWriter;
import com.vanmool.orderservice.models.Order;
import com.vanmool.orderservice.models.OrderStatus;
import com.vanmool.orderservice.models.ShippingAddress;
import com.vanmool.orderservice.repositories.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Unit test of the order rules. The repository and both service clients are mocks, so every failure along the
// distributed checkout (cart down, stock short, product-service timing out, the database failing) can be staged.
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    private static final UUID USER_ID = UUID.fromString("a8c1e6ff-556d-4dd2-a238-148b3fca351c");
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final UUID BISCUITS_ID = UUID.fromString("837a0c5a-5181-4a65-8e10-32759b931990");
    private static final String TOKEN = "eyJ.customer.token";
    private static final Customer CUSTOMER = new Customer(USER_ID, "asha@example.com", TOKEN);
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final ShippingAddressDto ADDRESS = new ShippingAddressDto("Asha Gamit", "9876543210",
            "12 Station Road", "  ", "Ahwa", "Gujarat", "394710");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CartClient cartClient;

    @Mock
    private ProductClient productClient;

    @Mock
    private OutboxWriter outboxWriter;

    private OrderService orderService;

    // A real TransactionTemplate around a mocked transaction manager: the callback runs, no database is involved
    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, cartClient, productClient, outboxWriter,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC),
                java.time.Duration.ofMinutes(60));
    }

    private static List<CartClient.CartLine> twoBasketsAndThreePackets() {
        return List.of(new CartClient.CartLine(BASKET_ID, 2), new CartClient.CartLine(BISCUITS_ID, 3));
    }

    private static ProductClient.Reservation reservation(UUID orderId) {
        return new ProductClient.Reservation(orderId, List.of(
                new ProductClient.ReservedItem(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2),
                new ProductClient.ReservedItem(BISCUITS_ID, "Ragi Biscuits", new BigDecimal("120.00"), 3)));
    }

    private static Order order(UUID id, OrderStatus status) {
        Order order = new Order();
        order.setId(id);
        order.setUserId(USER_ID);
        order.setStatus(status);
        order.setShippingAddress(ADDRESS.toShippingAddress());
        order.addItem(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 1);
        order.setTotalAmount(order.calculateTotal());
        return order;
    }

    // The order id is chosen inside placeOrder; the reservation echoes whatever id it was given
    private void reservationSucceeds() {
        when(productClient.reserve(any(UUID.class), eq(twoBasketsAndThreePackets())))
                .thenAnswer(invocation -> reservation(invocation.getArgument(0)));
    }

    private void saveReturnsItsArgument() {
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    @DisplayName("placeOrder")
    class PlaceOrder {
        @Test
        void placesTheOrderAtTheReservedPrices_andEmptiesTheCart() {
            // Arrange
            when(cartClient.getCartLines(TOKEN)).thenReturn(twoBasketsAndThreePackets());
            reservationSucceeds();
            saveReturnsItsArgument();

            // Act
            OrderResponseDto order = orderService.placeOrder(CUSTOMER, ADDRESS, null);

            // Assert: 2 x 499.99 + 3 x 120.00
            assertEquals(OrderStatus.PENDING_PAYMENT, order.status());
            assertEquals(new BigDecimal("1359.98"), order.totalAmount());
            assertEquals(List.of(new BigDecimal("999.98"), new BigDecimal("360.00")),
                    order.items().stream().map(item -> item.lineTotal()).toList());
            assertEquals("Ragi Biscuits", order.items().get(1).name());
            verify(cartClient).clearCart(TOKEN);
        }

        @Test
        void recordsOrderPlaced_withTheCustomersEmailItemsAndTotal_keyedByTheOrderId() {
            // Arrange
            when(cartClient.getCartLines(TOKEN)).thenReturn(twoBasketsAndThreePackets());
            reservationSucceeds();
            saveReturnsItsArgument();
            ArgumentCaptor<OrderPlacedEvent> event = ArgumentCaptor.forClass(OrderPlacedEvent.class);

            // Act
            OrderResponseDto order = orderService.placeOrder(CUSTOMER, ADDRESS, null);

            // Assert
            verify(outboxWriter).add(eq("order-events"), eq(order.id().toString()), event.capture());
            assertEquals("OrderPlaced", event.getValue().eventType());
            assertEquals(order.id(), event.getValue().orderId());
            assertEquals(USER_ID, event.getValue().userId());
            assertEquals("asha@example.com", event.getValue().customerEmail());
            assertEquals(new BigDecimal("1359.98"), event.getValue().totalAmount());
            assertEquals(2, event.getValue().items().size());
            assertEquals("394710", event.getValue().shippingAddress().pincode());
            assertEquals(NOW, event.getValue().occurredAt());
        }

        @Test
        void savesTheOrderForTheUser_underTheIdTheStockWasReservedWith() {
            // Arrange
            when(cartClient.getCartLines(TOKEN)).thenReturn(twoBasketsAndThreePackets());
            reservationSucceeds();
            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            when(orderRepository.saveAndFlush(saved.capture())).thenAnswer(invocation -> invocation.getArgument(0));
            ArgumentCaptor<UUID> reservedUnder = ArgumentCaptor.forClass(UUID.class);

            // Act
            OrderResponseDto order = orderService.placeOrder(CUSTOMER, ADDRESS, "checkout-1");

            // Assert
            verify(productClient).reserve(reservedUnder.capture(), any());
            assertEquals(reservedUnder.getValue(), saved.getValue().getId());
            assertEquals(order.id(), saved.getValue().getId());
            assertEquals(USER_ID, saved.getValue().getUserId());
            assertEquals("checkout-1", saved.getValue().getIdempotencyKey());
            assertEquals("asha@example.com", saved.getValue().getCustomerEmail());
            // The blank second address line is stored as no line at all
            ShippingAddress address = saved.getValue().getShippingAddress();
            assertEquals("Ahwa", address.getCity());
            assertNull(address.getLine2());
        }

        @Test
        void emptyCart_isRefused_beforeAnyStockIsReserved() {
            // Arrange
            when(cartClient.getCartLines(TOKEN)).thenReturn(List.of());

            // Act & Assert
            assertThrows(CartEmptyException.class, () -> orderService.placeOrder(CUSTOMER, ADDRESS, null));
            verifyNoInteractions(productClient);
            verify(orderRepository, never()).saveAndFlush(any());
        }

        @Test
        void cartServiceDown_failsWithoutReservingAnything() {
            // Arrange
            when(cartClient.getCartLines(TOKEN)).thenThrow(new CartServiceUnavailableException(new RuntimeException("down")));

            // Act & Assert
            assertThrows(CartServiceUnavailableException.class, () -> orderService.placeOrder(CUSTOMER, ADDRESS, null));
            verifyNoInteractions(productClient);
        }

        @Test
        void notEnoughStock_failsAndSavesNothing_andNeedsNoRelease() {
            // Arrange: product-service took nothing (all or nothing), so there is nothing to give back
            when(cartClient.getCartLines(TOKEN)).thenReturn(twoBasketsAndThreePackets());
            when(productClient.reserve(any(UUID.class), any()))
                    .thenThrow(new InsufficientStockException("Not enough stock for products [" + BISCUITS_ID + "]"));

            // Act & Assert
            assertThrows(InsufficientStockException.class, () -> orderService.placeOrder(CUSTOMER, ADDRESS, null));
            verify(orderRepository, never()).saveAndFlush(any());
            verify(productClient, never()).release(any());
            verify(cartClient, never()).clearCart(any());
        }

        @Test
        void productServiceTimingOut_releasesTheOrderId_inCaseTheUnitsWereTaken() {
            // Arrange
            when(cartClient.getCartLines(TOKEN)).thenReturn(twoBasketsAndThreePackets());
            ArgumentCaptor<UUID> orderId = ArgumentCaptor.forClass(UUID.class);
            when(productClient.reserve(orderId.capture(), any()))
                    .thenThrow(new ProductServiceUnavailableException(new RuntimeException("Read timed out")));

            // Act & Assert
            assertThrows(ProductServiceUnavailableException.class, () -> orderService.placeOrder(CUSTOMER, ADDRESS, null));
            verify(productClient).release(orderId.getValue());
            verify(orderRepository, never()).saveAndFlush(any());
        }

        @Test
        void databaseFailingToSaveTheOrder_givesTheReservedUnitsBack() {
            // Arrange
            when(cartClient.getCartLines(TOKEN)).thenReturn(twoBasketsAndThreePackets());
            reservationSucceeds();
            ArgumentCaptor<UUID> orderId = ArgumentCaptor.forClass(UUID.class);
            when(orderRepository.saveAndFlush(any(Order.class)))
                    .thenThrow(new DataAccessResourceFailureException("Communications link failure"));

            // Act
            assertThrows(DataAccessResourceFailureException.class, () -> orderService.placeOrder(CUSTOMER, ADDRESS, null));

            // Assert: the units reserved under this order id are released, the cart is kept, no event recorded
            verify(productClient).reserve(orderId.capture(), any());
            verify(productClient).release(orderId.getValue());
            verify(cartClient, never()).clearCart(any());
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void releaseFailingToo_isLogged_andTheOriginalErrorIsReported() {
            // Arrange
            when(cartClient.getCartLines(TOKEN)).thenReturn(twoBasketsAndThreePackets());
            reservationSucceeds();
            when(orderRepository.saveAndFlush(any(Order.class)))
                    .thenThrow(new DataAccessResourceFailureException("Communications link failure"));
            doThrow(new ProductServiceUnavailableException(new RuntimeException("down"))).when(productClient).release(any());

            // Act & Assert: the database error, not the release error, reaches the client
            assertThrows(DataAccessResourceFailureException.class, () -> orderService.placeOrder(CUSTOMER, ADDRESS, null));
        }

        @Test
        void cartThatCannotBeEmptied_doesNotUndoThePlacedOrder() {
            // Arrange
            when(cartClient.getCartLines(TOKEN)).thenReturn(twoBasketsAndThreePackets());
            reservationSucceeds();
            saveReturnsItsArgument();
            doThrow(new CartServiceUnavailableException(new RuntimeException("down"))).when(cartClient).clearCart(TOKEN);

            // Act & Assert
            assertEquals(OrderStatus.PENDING_PAYMENT, orderService.placeOrder(CUSTOMER, ADDRESS, null).status());
            verify(productClient, never()).release(any());
        }

        @Test
        void sameIdempotencyKeyAgain_returnsTheFirstOrder_withoutTouchingAnyService() {
            // Arrange
            Order first = order(UUID.randomUUID(), OrderStatus.PENDING_PAYMENT);
            when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, "checkout-1")).thenReturn(Optional.of(first));

            // Act
            OrderResponseDto order = orderService.placeOrder(CUSTOMER, ADDRESS, "checkout-1");

            // Assert
            assertEquals(first.getId(), order.id());
            verifyNoInteractions(cartClient, productClient);
        }

        @Test
        void sameIdempotencyKeyAtTheSameMoment_releasesTheSecondReservation_andReturnsTheFirstOrder() {
            // Arrange: both requests found no order with the key; the unique key lets only the first one save
            Order first = order(UUID.randomUUID(), OrderStatus.PENDING_PAYMENT);
            when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, "checkout-1"))
                    .thenReturn(Optional.empty(), Optional.of(first));
            when(cartClient.getCartLines(TOKEN)).thenReturn(twoBasketsAndThreePackets());
            reservationSucceeds();
            when(orderRepository.saveAndFlush(any(Order.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate entry for key 'uk_orders_user_idempotency_key'"));
            ArgumentCaptor<UUID> secondOrderId = ArgumentCaptor.forClass(UUID.class);

            // Act
            OrderResponseDto order = orderService.placeOrder(CUSTOMER, ADDRESS, "checkout-1");

            // Assert
            assertEquals(first.getId(), order.id());
            verify(productClient).reserve(secondOrderId.capture(), any());
            verify(productClient).release(secondOrderId.getValue());
        }
    }

    @Nested
    @DisplayName("cancelOrder")
    class CancelOrder {
        @Test
        void pendingOrder_isMarkedCancelled_andOrderCancelledIsRecorded_forProductServiceToReleaseTheStock() {
            // Arrange
            UUID orderId = UUID.randomUUID();
            when(orderRepository.findByIdAndUserId(orderId, USER_ID)).thenReturn(
                    Optional.of(order(orderId, OrderStatus.PENDING_PAYMENT)), Optional.of(order(orderId, OrderStatus.CANCELLED)));
            when(orderRepository.moveStatus(orderId, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED)).thenReturn(1);
            ArgumentCaptor<OrderCancelledEvent> event = ArgumentCaptor.forClass(OrderCancelledEvent.class);

            // Act
            OrderResponseDto order = orderService.cancelOrder(CUSTOMER, orderId);

            // Assert: the status change first, then the event, in one transaction; no direct call to product-service
            InOrder steps = inOrder(orderRepository, outboxWriter);
            steps.verify(orderRepository).moveStatus(orderId, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED);
            steps.verify(outboxWriter).add(eq("order-events"), eq(orderId.toString()), event.capture());
            assertEquals("OrderCancelled", event.getValue().eventType());
            assertEquals("CUSTOMER", event.getValue().reason());
            assertEquals(orderId, event.getValue().orderId());
            assertEquals("asha@example.com", event.getValue().customerEmail());
            verifyNoInteractions(productClient);
            assertEquals(OrderStatus.CANCELLED, order.status());
        }

        @Test
        void confirmedOrder_cannotBeCancelled_andNothingChanges() {
            // Arrange
            UUID orderId = UUID.randomUUID();
            when(orderRepository.findByIdAndUserId(orderId, USER_ID)).thenReturn(Optional.of(order(orderId, OrderStatus.CONFIRMED)));

            // Act & Assert
            InvalidOrderStateException exception = assertThrows(InvalidOrderStateException.class,
                    () -> orderService.cancelOrder(CUSTOMER, orderId));
            assertEquals("An order that is CONFIRMED cannot be cancelled", exception.getMessage());
            verify(orderRepository, never()).moveStatus(any(), any(), any());
            verifyNoInteractions(productClient, outboxWriter);
        }

        @Test
        void orderChangedInTheMeantime_isNotCancelled_andItsStockIsKept() {
            // Arrange: the payment confirmed the order between reading it and the UPDATE
            UUID orderId = UUID.randomUUID();
            when(orderRepository.findByIdAndUserId(orderId, USER_ID)).thenReturn(Optional.of(order(orderId, OrderStatus.PENDING_PAYMENT)));
            when(orderRepository.moveStatus(orderId, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED)).thenReturn(0);

            // Act & Assert: and no event, since nothing was cancelled
            assertThrows(InvalidOrderStateException.class, () -> orderService.cancelOrder(CUSTOMER, orderId));
            verifyNoInteractions(productClient, outboxWriter);
        }

        @Test
        void cancellingACancelledOrderAgain_changesNothing_andPublishesNothing() {
            // Arrange
            UUID orderId = UUID.randomUUID();
            when(orderRepository.findByIdAndUserId(orderId, USER_ID)).thenReturn(Optional.of(order(orderId, OrderStatus.CANCELLED)));

            // Act
            OrderResponseDto order = orderService.cancelOrder(CUSTOMER, orderId);

            // Assert: the first cancel's event already releases the stock
            assertEquals(OrderStatus.CANCELLED, order.status());
            verify(orderRepository, never()).moveStatus(any(), any(), any());
            verifyNoInteractions(productClient, outboxWriter);
        }

        @Test
        void anotherCustomersOrder_isNotFound() {
            // Arrange: the query is scoped to the user, so another customer's order id finds nothing
            UUID orderId = UUID.randomUUID();
            when(orderRepository.findByIdAndUserId(orderId, USER_ID)).thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(OrderNotFoundException.class, () -> orderService.cancelOrder(CUSTOMER, orderId));
            verifyNoInteractions(productClient);
        }
    }

    @Nested
    @DisplayName("payments and expiry")
    class Payments {
        @Test
        void paymentSucceeded_confirmsThePendingOrder() {
            UUID orderId = UUID.randomUUID();
            when(orderRepository.moveStatus(orderId, OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED)).thenReturn(1);

            orderService.confirmPayment(orderId, UUID.randomUUID());

            verify(orderRepository).moveStatus(orderId, OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED);
            verify(orderRepository, never()).findById(any());
        }

        @Test
        void sameEventAgain_orAPaymentForACancelledOrder_changesNothing() {
            // A second delivery finds the order CONFIRMED; a late payment finds it CANCELLED (logged for a refund)
            UUID confirmedId = UUID.randomUUID();
            UUID cancelledId = UUID.randomUUID();
            when(orderRepository.moveStatus(any(), eq(OrderStatus.PENDING_PAYMENT), eq(OrderStatus.CONFIRMED))).thenReturn(0);
            when(orderRepository.findById(confirmedId)).thenReturn(Optional.of(order(confirmedId, OrderStatus.CONFIRMED)));
            when(orderRepository.findById(cancelledId)).thenReturn(Optional.of(order(cancelledId, OrderStatus.CANCELLED)));

            orderService.confirmPayment(confirmedId, UUID.randomUUID());
            orderService.confirmPayment(cancelledId, UUID.randomUUID());

            verifyNoInteractions(outboxWriter);
        }

        @Test
        void unpaidOrdersPastTheWindow_areCancelledWithTheReason_andTheirStockIsReleasedThroughTheEvent() {
            // Arrange: two unpaid orders older than 60 minutes; the second was paid just before the UPDATE
            Order unpaid = order(UUID.randomUUID(), OrderStatus.PENDING_PAYMENT);
            unpaid.setCustomerEmail("asha@example.com");
            Order paidMeanwhile = order(UUID.randomUUID(), OrderStatus.PENDING_PAYMENT);
            when(orderRepository.findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(OrderStatus.PENDING_PAYMENT,
                    NOW.minus(java.time.Duration.ofMinutes(60)))).thenReturn(List.of(unpaid, paidMeanwhile));
            when(orderRepository.moveStatus(unpaid.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED)).thenReturn(1);
            when(orderRepository.moveStatus(paidMeanwhile.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED)).thenReturn(0);
            ArgumentCaptor<OrderCancelledEvent> event = ArgumentCaptor.forClass(OrderCancelledEvent.class);

            // Act
            int expired = orderService.expireUnpaidOrders();

            // Assert: only the first is cancelled
            assertEquals(1, expired);
            verify(outboxWriter).add(eq("order-events"), eq(unpaid.getId().toString()), event.capture());
            assertEquals("PAYMENT_TIMEOUT", event.getValue().reason());
            assertEquals("asha@example.com", event.getValue().customerEmail());
        }
    }

    @Test
    void getOrder_returnsTheCustomersOrder() {
        // Arrange
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndUserId(orderId, USER_ID)).thenReturn(Optional.of(order(orderId, OrderStatus.PENDING_PAYMENT)));

        // Act & Assert
        assertEquals(orderId, orderService.getOrder(USER_ID, orderId).id());
    }

    @Test
    void getOrders_isAlwaysNewestFirst_withTheIdAsTieBreaker() {
        // Arrange: the client asked to sort by something else
        Pageable requested = PageRequest.of(2, 5, Sort.by("totalAmount"));
        ArgumentCaptor<Pageable> used = ArgumentCaptor.forClass(Pageable.class);
        Order order = order(UUID.randomUUID(), OrderStatus.PENDING_PAYMENT);
        when(orderRepository.findByUserId(eq(USER_ID), used.capture())).thenReturn(new PageImpl<>(List.of(order)));

        // Act
        var page = orderService.getOrders(USER_ID, requested);

        // Assert
        assertEquals(PageRequest.of(2, 5, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))), used.getValue());
        assertSame(order.getId(), page.getContent().get(0).id());
    }
}
