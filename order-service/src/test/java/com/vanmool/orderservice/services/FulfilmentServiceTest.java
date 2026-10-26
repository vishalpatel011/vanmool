package com.vanmool.orderservice.services;

import com.vanmool.orderservice.dtos.OrderResponseDto;
import com.vanmool.orderservice.dtos.ShipmentDto;
import com.vanmool.orderservice.events.OrderDeliveredEvent;
import com.vanmool.orderservice.events.OrderShippedEvent;
import com.vanmool.orderservice.events.OutboxWriter;
import com.vanmool.orderservice.exceptions.InvalidOrderStateException;
import com.vanmool.orderservice.exceptions.OrderNotFoundException;
import com.vanmool.orderservice.models.Order;
import com.vanmool.orderservice.models.OrderStatus;
import com.vanmool.orderservice.models.OrderStatusChange;
import com.vanmool.orderservice.models.ShippingAddress;
import com.vanmool.orderservice.repositories.OrderRepository;
import com.vanmool.orderservice.repositories.OrderStatusChangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Unit test of shipping and delivering orders. The repositories and the outbox are mocks, so the conditional UPDATEs
// can be made to win or lose, as when two people of the team click at the same moment.
@ExtendWith(MockitoExtension.class)
class FulfilmentServiceTest {
    private static final String ADMIN_ID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    private static final UUID ORDER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final Instant NOW = Instant.parse("2026-10-27T05:30:00Z");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusChangeRepository statusChangeRepository;

    @Mock
    private OutboxWriter outboxWriter;

    private FulfilmentService fulfilmentService;

    @BeforeEach
    void setUp() {
        fulfilmentService = new FulfilmentService(orderRepository, statusChangeRepository,
                new OrderResponses(statusChangeRepository), outboxWriter,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Order order(OrderStatus status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(UUID.fromString("a8c1e6ff-556d-4dd2-a238-148b3fca351c"));
        order.setStatus(status);
        order.setCustomerEmail("asha@example.com");
        order.setShippingAddress(new ShippingAddress("Asha Gamit", "9876543210", "12 Station Road", null, "Ahwa",
                "Gujarat", "394710"));
        order.addItem(UUID.randomUUID(), "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2);
        order.setTotalAmount(order.calculateTotal());
        return order;
    }

    private static Order shipped(String carrier, String trackingNumber) {
        Order order = order(OrderStatus.SHIPPED);
        order.setCarrier(carrier);
        order.setTrackingNumber(trackingNumber);
        return order;
    }

    @Nested
    @DisplayName("ship")
    class Ship {
        @Test
        void confirmedOrder_isShipped_withItsTimelineRow_andOrderShipped() {
            // Arrange: the second read is the order after the UPDATE
            when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.CONFIRMED)),
                    Optional.of(shipped("India Post", "EE123456789IN")));
            when(orderRepository.ship(ORDER_ID, "India Post", "EE123456789IN")).thenReturn(1);
            ArgumentCaptor<OrderStatusChange> change = ArgumentCaptor.forClass(OrderStatusChange.class);
            ArgumentCaptor<OrderShippedEvent> event = ArgumentCaptor.forClass(OrderShippedEvent.class);

            // Act: the carrier name as typed, with spaces around it
            OrderResponseDto order = fulfilmentService.ship(ADMIN_ID, ORDER_ID, "  India Post ", "EE123456789IN");

            // Assert: UPDATE, then the timeline and the event, in that order
            InOrder steps = inOrder(orderRepository, statusChangeRepository, outboxWriter);
            steps.verify(orderRepository).ship(ORDER_ID, "India Post", "EE123456789IN");
            steps.verify(statusChangeRepository).save(change.capture());
            steps.verify(outboxWriter).add(eq("order-events"), eq(ORDER_ID.toString()), event.capture());
            assertEquals(OrderStatus.SHIPPED, change.getValue().getStatus());
            assertEquals("India Post, tracking EE123456789IN", change.getValue().getDetail());
            assertEquals(NOW, change.getValue().getChangedAt());
            assertEquals("OrderShipped", event.getValue().eventType());
            assertEquals("asha@example.com", event.getValue().customerEmail());
            assertEquals("EE123456789IN", event.getValue().trackingNumber());
            assertEquals(OrderStatus.SHIPPED, order.status());
            assertEquals(new ShipmentDto("India Post", "EE123456789IN"), order.shipment());
        }

        @Test
        void withoutATrackingNumber_theDetailNamesOnlyTheCarrier() {
            // Arrange: a cooperative delivering nearby orders itself
            when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.CONFIRMED)),
                    Optional.of(shipped("Dangs Artisan Cooperative", null)));
            when(orderRepository.ship(ORDER_ID, "Dangs Artisan Cooperative", null)).thenReturn(1);
            ArgumentCaptor<OrderStatusChange> change = ArgumentCaptor.forClass(OrderStatusChange.class);

            // Act
            fulfilmentService.ship(ADMIN_ID, ORDER_ID, "Dangs Artisan Cooperative", null);

            // Assert
            verify(statusChangeRepository).save(change.capture());
            assertEquals("Dangs Artisan Cooperative", change.getValue().getDetail());
        }

        @Test
        void sameShipmentAgain_changesNothing_andPublishesNothing() {
            // Arrange: a retry after a timeout
            when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(shipped("India Post", "EE123456789IN")));

            // Act
            OrderResponseDto order = fulfilmentService.ship(ADMIN_ID, ORDER_ID, "India Post", "EE123456789IN");

            // Assert
            assertEquals(OrderStatus.SHIPPED, order.status());
            verify(orderRepository, never()).ship(any(), anyString(), any());
            verifyNoInteractions(outboxWriter);
            verify(statusChangeRepository, never()).save(any());
        }

        @Test
        void alreadyShippedWithOtherDetails_isRefused_namingTheFirstShipment() {
            when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(shipped("India Post", "EE123456789IN")));

            InvalidOrderStateException exception = assertThrows(InvalidOrderStateException.class,
                    () -> fulfilmentService.ship(ADMIN_ID, ORDER_ID, "Delhivery", "DL98765"));
            assertEquals("The order was already shipped: India Post, tracking EE123456789IN", exception.getMessage());
            verifyNoInteractions(outboxWriter);
        }

        // Not paid yet, cancelled, or already delivered
        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "CANCELLED", "DELIVERED"})
        void orderThatIsNotConfirmed_cannotBeShipped(OrderStatus status) {
            when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(order(status)));

            InvalidOrderStateException exception = assertThrows(InvalidOrderStateException.class,
                    () -> fulfilmentService.ship(ADMIN_ID, ORDER_ID, "India Post", "EE123456789IN"));
            assertEquals("An order that is " + status + " cannot be shipped", exception.getMessage());
            verify(orderRepository, never()).ship(any(), anyString(), any());
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void orderChangedInTheMeantime_isNotShipped_andNothingIsRecorded() {
            // Arrange: someone else shipped it between the read and the UPDATE
            when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.CONFIRMED)));
            when(orderRepository.ship(ORDER_ID, "India Post", "EE123456789IN")).thenReturn(0);

            // Act & Assert
            assertThrows(InvalidOrderStateException.class,
                    () -> fulfilmentService.ship(ADMIN_ID, ORDER_ID, "India Post", "EE123456789IN"));
            verify(statusChangeRepository, never()).save(any());
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void unknownOrder_isNotFound() {
            when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(Optional.empty());

            assertThrows(OrderNotFoundException.class,
                    () -> fulfilmentService.ship(ADMIN_ID, ORDER_ID, "India Post", "EE123456789IN"));
        }
    }

    @Nested
    @DisplayName("markDelivered")
    class Deliver {
        @Test
        void shippedOrder_isDelivered_withItsTimelineRow_andOrderDelivered() {
            // Arrange
            Order delivered = shipped("India Post", "EE123456789IN");
            delivered.setStatus(OrderStatus.DELIVERED);
            when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(
                    Optional.of(shipped("India Post", "EE123456789IN")), Optional.of(delivered));
            when(orderRepository.moveStatus(ORDER_ID, OrderStatus.SHIPPED, OrderStatus.DELIVERED)).thenReturn(1);
            ArgumentCaptor<OrderStatusChange> change = ArgumentCaptor.forClass(OrderStatusChange.class);
            ArgumentCaptor<OrderDeliveredEvent> event = ArgumentCaptor.forClass(OrderDeliveredEvent.class);

            // Act
            OrderResponseDto order = fulfilmentService.markDelivered(ADMIN_ID, ORDER_ID);

            // Assert
            verify(statusChangeRepository).save(change.capture());
            verify(outboxWriter).add(eq("order-events"), eq(ORDER_ID.toString()), event.capture());
            assertEquals(OrderStatus.DELIVERED, change.getValue().getStatus());
            assertNull(change.getValue().getDetail());
            assertEquals("OrderDelivered", event.getValue().eventType());
            assertEquals(ORDER_ID, event.getValue().orderId());
            assertEquals(OrderStatus.DELIVERED, order.status());
        }

        @Test
        void deliveredOrderAgain_changesNothing() {
            Order delivered = shipped("India Post", "EE123456789IN");
            delivered.setStatus(OrderStatus.DELIVERED);
            when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(delivered));

            assertEquals(OrderStatus.DELIVERED, fulfilmentService.markDelivered(ADMIN_ID, ORDER_ID).status());
            verify(orderRepository, never()).moveStatus(any(), any(), any());
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void orderThatWasNotShipped_cannotBeDelivered() {
            when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.CONFIRMED)));

            InvalidOrderStateException exception = assertThrows(InvalidOrderStateException.class,
                    () -> fulfilmentService.markDelivered(ADMIN_ID, ORDER_ID));
            assertEquals("An order that is CONFIRMED cannot be marked delivered", exception.getMessage());
            verifyNoInteractions(outboxWriter);
        }
    }

    @Test
    void ordersToSend_areOldestFirst_whateverTheClientAskedFor() {
        // Arrange
        Pageable requested = PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "totalAmount"));
        ArgumentCaptor<Pageable> used = ArgumentCaptor.forClass(Pageable.class);
        when(orderRepository.findByStatus(eq(OrderStatus.CONFIRMED), used.capture()))
                .thenReturn(new PageImpl<>(List.of(order(OrderStatus.CONFIRMED))));

        // Act
        var page = fulfilmentService.getOrders(OrderStatus.CONFIRMED, requested);

        // Assert: first come, first sent
        assertEquals(PageRequest.of(1, 20, Sort.by("createdAt").and(Sort.by("id"))), used.getValue());
        assertEquals(ORDER_ID, page.getContent().get(0).id());
    }
}
