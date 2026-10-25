package com.vanmool.productservice.services;

import com.vanmool.productservice.dtos.ReservationItemRequestDto;
import com.vanmool.productservice.dtos.ReservedItemResponseDto;
import com.vanmool.productservice.exceptions.InsufficientStockException;
import com.vanmool.productservice.exceptions.InvalidRequestException;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.exceptions.ReservationConflictException;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.models.ReservationStatus;
import com.vanmool.productservice.models.ReservedItem;
import com.vanmool.productservice.models.Stock;
import com.vanmool.productservice.models.StockReservation;
import com.vanmool.productservice.repositories.ProductRepository;
import com.vanmool.productservice.repositories.StockRepository;
import com.vanmool.productservice.repositories.StockReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Unit test of the stock rules; the repositories are mocks. That the SQL really is atomic, rolls back and never
// oversells is shown against MySQL in StockIT.
@ExtendWith(MockitoExtension.class)
class StockServiceTest {
    private static final UUID ORDER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    // Locking order is UUID.compareTo order. It compares the two 64-bit halves as signed numbers, so BISCUITS_ID
    // (837a0c5a..., top bit set: negative) comes before BASKET_ID (150dac81...). Any fixed order prevents deadlocks
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final UUID BISCUITS_ID = UUID.fromString("837a0c5a-5181-4a65-8e10-32759b931990");

    @Mock
    private ProductRepository productRepository;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private StockReservationRepository reservationRepository;

    @InjectMocks
    private StockService stockService;

    private static Product product(UUID id, String name, String price) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        return product;
    }

    private static ReservationItemRequestDto item(UUID productId, int quantity) {
        ReservationItemRequestDto item = new ReservationItemRequestDto();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    private static StockReservation reservation(ReservationStatus status, ReservedItem... items) {
        StockReservation reservation = new StockReservation();
        reservation.setOrderId(ORDER_ID);
        reservation.setStatus(status);
        reservation.getItems().addAll(List.of(items));
        return reservation;
    }

    private static Stock stock(UUID productId, int quantity) {
        Stock stock = new Stock();
        stock.setProductId(productId);
        stock.setQuantity(quantity);
        return stock;
    }

    @Nested
    @DisplayName("reserve")
    class Reserve {
        @Test
        void takesTheUnitsOfEveryProduct_inProductIdOrder_andKeepsTheirNamesAndPrices() throws ProductNotFoundException {
            // Arrange: the request lists the basket first
            when(reservationRepository.findById(ORDER_ID)).thenReturn(Optional.empty());
            when(productRepository.findAllByIdInAndDeletedFalse(any())).thenReturn(List.of(
                    product(BASKET_ID, "Handcrafted Bamboo Basket", "499.99"), product(BISCUITS_ID, "Ragi Biscuits", "120.00")));
            when(stockRepository.take(any(), anyInt())).thenReturn(1);
            when(reservationRepository.save(any(StockReservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            StockService.ReservationResult result = stockService.reserve(ORDER_ID,
                    List.of(item(BASKET_ID, 2), item(BISCUITS_ID, 3)));

            // Assert: rows are locked in product id order, whatever the request order, so two orders never deadlock
            InOrder order = inOrder(stockRepository);
            order.verify(stockRepository).take(BISCUITS_ID, 3);
            order.verify(stockRepository).take(BASKET_ID, 2);
            assertTrue(result.created());
            assertEquals(ReservationStatus.RESERVED, result.reservation().status());
            assertEquals(List.of(
                    new ReservedItemResponseDto(BISCUITS_ID, "Ragi Biscuits", new BigDecimal("120.00"), 3),
                    new ReservedItemResponseDto(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2)),
                    result.reservation().items());
        }

        @Test
        void tooFewUnitsOfAnyProduct_throwsNamingThem_andSavesNoReservation() {
            // Arrange: the basket can be taken, the biscuits cannot
            when(reservationRepository.findById(ORDER_ID)).thenReturn(Optional.empty());
            when(productRepository.findAllByIdInAndDeletedFalse(any())).thenReturn(List.of(
                    product(BASKET_ID, "Handcrafted Bamboo Basket", "499.99"), product(BISCUITS_ID, "Ragi Biscuits", "120.00")));
            when(stockRepository.take(BASKET_ID, 2)).thenReturn(1);
            when(stockRepository.take(BISCUITS_ID, 3)).thenReturn(0);

            // Act: the exception rolls back the transaction, which gives the basket's units back (StockIT)
            InsufficientStockException exception = assertThrows(InsufficientStockException.class,
                    () -> stockService.reserve(ORDER_ID, List.of(item(BASKET_ID, 2), item(BISCUITS_ID, 3))));

            // Assert
            assertEquals("Not enough stock for products [" + BISCUITS_ID + "]", exception.getMessage());
            verify(reservationRepository, never()).save(any());
        }

        @Test
        void productMissingOrDeleted_throwsBeforeAnyStockIsTaken() {
            // Arrange
            when(reservationRepository.findById(ORDER_ID)).thenReturn(Optional.empty());
            when(productRepository.findAllByIdInAndDeletedFalse(any()))
                    .thenReturn(List.of(product(BASKET_ID, "Handcrafted Bamboo Basket", "499.99")));

            // Act & Assert
            ProductNotFoundException exception = assertThrows(ProductNotFoundException.class,
                    () -> stockService.reserve(ORDER_ID, List.of(item(BASKET_ID, 1), item(BISCUITS_ID, 1))));
            assertTrue(exception.getMessage().contains(BISCUITS_ID.toString()));
            verifyNoInteractions(stockRepository);
        }

        @Test
        void sameProductTwice_isRefused() {
            assertThrows(InvalidRequestException.class,
                    () -> stockService.reserve(ORDER_ID, List.of(item(BASKET_ID, 1), item(BASKET_ID, 2))));
            verifyNoInteractions(stockRepository, reservationRepository);
        }

        @Test
        void sameRequestAgain_returnsTheFirstReservation_withoutTakingStockAgain() throws ProductNotFoundException {
            // Arrange: order-service retries after a timeout, but the first request had succeeded
            when(reservationRepository.findById(ORDER_ID)).thenReturn(Optional.of(reservation(ReservationStatus.RESERVED,
                    new ReservedItem(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2))));

            // Act
            StockService.ReservationResult result = stockService.reserve(ORDER_ID, List.of(item(BASKET_ID, 2)));

            // Assert
            assertFalse(result.created());
            assertEquals(new BigDecimal("499.99"), result.reservation().items().get(0).unitPrice());
            verifyNoInteractions(stockRepository);
        }

        @Test
        void sameOrderWithOtherItems_isAConflict() {
            // Arrange
            when(reservationRepository.findById(ORDER_ID)).thenReturn(Optional.of(reservation(ReservationStatus.RESERVED,
                    new ReservedItem(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2))));

            // Act & Assert
            assertThrows(ReservationConflictException.class,
                    () -> stockService.reserve(ORDER_ID, List.of(item(BASKET_ID, 5))));
            verifyNoInteractions(stockRepository);
        }

        @Test
        void orderWhoseReservationWasReleased_cannotReserveAgain() {
            // Arrange
            when(reservationRepository.findById(ORDER_ID)).thenReturn(Optional.of(reservation(ReservationStatus.RELEASED,
                    new ReservedItem(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2))));

            // Act & Assert
            ReservationConflictException exception = assertThrows(ReservationConflictException.class,
                    () -> stockService.reserve(ORDER_ID, List.of(item(BASKET_ID, 2))));
            assertTrue(exception.getMessage().contains("released"));
        }
    }

    @Nested
    @DisplayName("release")
    class Release {
        @Test
        void reservedOrder_getsItsUnitsBack_inProductIdOrder_andIsMarkedReleased() {
            // Arrange
            StockReservation reservation = reservation(ReservationStatus.RESERVED,
                    new ReservedItem(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2),
                    new ReservedItem(BISCUITS_ID, "Ragi Biscuits", new BigDecimal("120.00"), 3));
            when(reservationRepository.findForUpdate(ORDER_ID)).thenReturn(Optional.of(reservation));

            // Act
            stockService.release(ORDER_ID);

            // Assert
            InOrder order = inOrder(stockRepository);
            order.verify(stockRepository).putBack(BISCUITS_ID, 3);
            order.verify(stockRepository).putBack(BASKET_ID, 2);
            assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
        }

        @Test
        void alreadyReleased_changesNothing() {
            // Arrange
            when(reservationRepository.findForUpdate(ORDER_ID)).thenReturn(Optional.of(reservation(ReservationStatus.RELEASED,
                    new ReservedItem(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2))));

            // Act
            stockService.release(ORDER_ID);

            // Assert
            verifyNoInteractions(stockRepository);
        }

        @Test
        void orderWithoutAReservation_changesNothing() {
            // Arrange: order-service releases after a failed reserve, which may never have reached the database
            when(reservationRepository.findForUpdate(ORDER_ID)).thenReturn(Optional.empty());

            // Act
            stockService.release(ORDER_ID);

            // Assert
            verifyNoInteractions(stockRepository);
        }
    }

    @Nested
    @DisplayName("adjustStock and getStock")
    class Adjust {
        @Test
        void addingUnits_addsThemAndReturnsTheNewCount() throws ProductNotFoundException {
            // Arrange
            when(productRepository.existsByIdAndDeletedFalse(BASKET_ID)).thenReturn(true);
            when(stockRepository.findById(BASKET_ID)).thenReturn(Optional.of(stock(BASKET_ID, 15)));

            // Act & Assert
            assertEquals(15, stockService.adjustStock(BASKET_ID, 10).quantity());
            verify(stockRepository).add(BASKET_ID, 10);
        }

        @Test
        void removingUnits_takesThem() throws ProductNotFoundException {
            // Arrange
            when(productRepository.existsByIdAndDeletedFalse(BASKET_ID)).thenReturn(true);
            when(stockRepository.take(BASKET_ID, 2)).thenReturn(1);
            when(stockRepository.findById(BASKET_ID)).thenReturn(Optional.of(stock(BASKET_ID, 3)));

            // Act & Assert
            assertEquals(3, stockService.adjustStock(BASKET_ID, -2).quantity());
        }

        @Test
        void removingMoreUnitsThanThereAre_isRefused() {
            // Arrange
            when(productRepository.existsByIdAndDeletedFalse(BASKET_ID)).thenReturn(true);
            when(stockRepository.take(BASKET_ID, 7)).thenReturn(0);

            // Act & Assert
            assertThrows(InsufficientStockException.class, () -> stockService.adjustStock(BASKET_ID, -7));
        }

        @Test
        void changeOfZero_isRefused() {
            InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                    () -> stockService.adjustStock(BASKET_ID, 0));
            assertEquals("change: must not be 0", exception.getMessage());
            verifyNoInteractions(stockRepository);
        }

        @Test
        void unknownProduct_isNotFound_andNothingChanges() {
            // Arrange
            when(productRepository.existsByIdAndDeletedFalse(BASKET_ID)).thenReturn(false);

            // Act & Assert
            assertThrows(ProductNotFoundException.class, () -> stockService.adjustStock(BASKET_ID, 5));
            assertThrows(ProductNotFoundException.class, () -> stockService.getStock(BASKET_ID));
            verifyNoInteractions(stockRepository);
        }

        @Test
        void productThatNeverHadStock_hasZero() throws ProductNotFoundException {
            // Arrange
            when(productRepository.existsByIdAndDeletedFalse(BASKET_ID)).thenReturn(true);
            when(stockRepository.findById(BASKET_ID)).thenReturn(Optional.empty());

            // Act & Assert
            assertEquals(0, stockService.getStock(BASKET_ID).quantity());
        }
    }

    @Test
    void savedReservation_holdsTheItemsInProductIdOrder() throws ProductNotFoundException {
        // Arrange
        when(reservationRepository.findById(ORDER_ID)).thenReturn(Optional.empty());
        when(productRepository.findAllByIdInAndDeletedFalse(any())).thenReturn(List.of(
                product(BASKET_ID, "Handcrafted Bamboo Basket", "499.99"), product(BISCUITS_ID, "Ragi Biscuits", "120.00")));
        when(stockRepository.take(any(), anyInt())).thenReturn(1);
        ArgumentCaptor<StockReservation> saved = ArgumentCaptor.forClass(StockReservation.class);
        when(reservationRepository.save(saved.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        stockService.reserve(ORDER_ID, List.of(item(BASKET_ID, 1), item(BISCUITS_ID, 1)));

        // Assert
        assertEquals(ORDER_ID, saved.getValue().getOrderId());
        assertEquals(List.of(BISCUITS_ID, BASKET_ID), saved.getValue().getItems().stream().map(ReservedItem::getProductId).toList());
    }
}
