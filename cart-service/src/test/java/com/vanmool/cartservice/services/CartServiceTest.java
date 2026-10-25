package com.vanmool.cartservice.services;

import com.vanmool.cartservice.clients.ProductClient;
import com.vanmool.cartservice.clients.ProductDetails;
import com.vanmool.cartservice.dtos.CartItemResponseDto;
import com.vanmool.cartservice.dtos.CartResponseDto;
import com.vanmool.cartservice.exceptions.CartLimitExceededException;
import com.vanmool.cartservice.exceptions.ProductNotFoundException;
import com.vanmool.cartservice.exceptions.ProductServiceUnavailableException;
import com.vanmool.cartservice.repositories.CartRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Unit test of the cart rules: Redis (CartRepository) and product-service (ProductClient) are Mockito mocks.
@ExtendWith(MockitoExtension.class)
class CartServiceTest {
    private static final UUID USER_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final UUID BISCUITS_ID = UUID.fromString("837a0c5a-5181-4a65-8e10-32759b931990");
    private static final UUID REMOVED_ID = UUID.fromString("20acea87-9f7c-4af0-a8db-39ed436a3237");
    private static final ProductDetails BASKET = new ProductDetails(BASKET_ID, "Handcrafted Bamboo Basket",
            new BigDecimal("499.99"), "https://example.com/images/bamboo-basket.jpg");
    private static final ProductDetails BISCUITS = new ProductDetails(BISCUITS_ID, "Ragi Biscuits",
            new BigDecimal("120.00"), null);

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductClient productClient;

    @InjectMocks
    private CartService cartService;

    private static Map<UUID, Integer> items(Object... productIdsAndQuantities) {
        Map<UUID, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < productIdsAndQuantities.length; i += 2) {
            items.put((UUID) productIdsAndQuantities[i], (Integer) productIdsAndQuantities[i + 1]);
        }
        return items;
    }

    @Nested
    @DisplayName("getCart")
    class GetCart {
        @Test
        void noCart_isAnEmptyCartWithZeroTotals() {
            // Arrange
            when(cartRepository.findItems(USER_ID)).thenReturn(Map.of());

            // Act
            CartResponseDto cart = cartService.getCart(USER_ID);

            // Assert
            assertEquals(List.of(), cart.items());
            assertEquals(0, cart.totalQuantity());
            assertEquals(BigDecimal.ZERO, cart.totalPrice());
            verifyNoInteractions(productClient);
        }

        @Test
        void pricesEachLineWithTheCurrentPrice_andAddsUpTheTotals() {
            // Arrange
            when(cartRepository.findItems(USER_ID)).thenReturn(items(BASKET_ID, 2, BISCUITS_ID, 3));
            when(productClient.getProduct(BASKET_ID)).thenReturn(BASKET);
            when(productClient.getProduct(BISCUITS_ID)).thenReturn(BISCUITS);

            // Act
            CartResponseDto cart = cartService.getCart(USER_ID);

            // Assert: 2 x 499.99 + 3 x 120.00
            assertEquals(new CartItemResponseDto(BASKET_ID, "Handcrafted Bamboo Basket",
                    "https://example.com/images/bamboo-basket.jpg", new BigDecimal("499.99"), 2,
                    new BigDecimal("999.98"), true), cart.items().get(0));
            assertEquals(new BigDecimal("360.00"), cart.items().get(1).lineTotal());
            assertEquals(5, cart.totalQuantity());
            assertEquals(new BigDecimal("1359.98"), cart.totalPrice());
        }

        @Test
        void linesAreSortedByName_whateverOrderRedisReturns() {
            // Arrange: Ragi Biscuits first in the hash
            when(cartRepository.findItems(USER_ID)).thenReturn(items(BISCUITS_ID, 1, BASKET_ID, 1));
            when(productClient.getProduct(BASKET_ID)).thenReturn(BASKET);
            when(productClient.getProduct(BISCUITS_ID)).thenReturn(BISCUITS);

            // Act & Assert
            assertEquals(List.of("Handcrafted Bamboo Basket", "Ragi Biscuits"),
                    cartService.getCart(USER_ID).items().stream().map(CartItemResponseDto::name).toList());
        }

        @Test
        void productRemovedFromTheCatalog_isShownAsUnavailable_andNotCounted() {
            // Arrange
            when(cartRepository.findItems(USER_ID)).thenReturn(items(REMOVED_ID, 4, BASKET_ID, 1));
            when(productClient.getProduct(REMOVED_ID)).thenThrow(new ProductNotFoundException(REMOVED_ID));
            when(productClient.getProduct(BASKET_ID)).thenReturn(BASKET);

            // Act
            CartResponseDto cart = cartService.getCart(USER_ID);

            // Assert: listed last, without a name or price, and left out of both totals
            CartItemResponseDto removed = cart.items().get(1);
            assertEquals(REMOVED_ID, removed.productId());
            assertFalse(removed.available());
            assertNull(removed.name());
            assertNull(removed.unitPrice());
            assertNull(removed.lineTotal());
            assertEquals(4, removed.quantity());
            assertTrue(cart.items().get(0).available());
            assertEquals(1, cart.totalQuantity());
            assertEquals(new BigDecimal("499.99"), cart.totalPrice());
        }

        @Test
        void productServiceDown_failsTheWholeRead() {
            // Arrange: a cart without prices would be misleading, so there is no partial answer
            when(cartRepository.findItems(USER_ID)).thenReturn(items(BASKET_ID, 1));
            when(productClient.getProduct(BASKET_ID))
                    .thenThrow(new ProductServiceUnavailableException("down", null));

            // Act & Assert
            assertThrows(ProductServiceUnavailableException.class, () -> cartService.getCart(USER_ID));
        }
    }

    @Nested
    @DisplayName("addItem")
    class AddItem {
        @Test
        void newProduct_isAddedWithItsQuantity() {
            // Arrange
            when(productClient.getProduct(BASKET_ID)).thenReturn(BASKET);
            when(cartRepository.containsProduct(USER_ID, BASKET_ID)).thenReturn(false);
            when(cartRepository.countProducts(USER_ID)).thenReturn(0L);
            when(cartRepository.addQuantity(USER_ID, BASKET_ID, 2)).thenReturn(2);
            when(cartRepository.findItems(USER_ID)).thenReturn(items(BASKET_ID, 2));

            // Act
            CartResponseDto cart = cartService.addItem(USER_ID, BASKET_ID, 2);

            // Assert: the answer is the updated, priced cart
            assertEquals(2, cart.totalQuantity());
            assertEquals(new BigDecimal("999.98"), cart.totalPrice());
        }

        @Test
        void productAlreadyInTheCart_addsToItsQuantity() {
            // Arrange: 3 in the cart, 2 more
            when(productClient.getProduct(BASKET_ID)).thenReturn(BASKET);
            when(cartRepository.containsProduct(USER_ID, BASKET_ID)).thenReturn(true);
            when(cartRepository.addQuantity(USER_ID, BASKET_ID, 2)).thenReturn(5);
            when(cartRepository.findItems(USER_ID)).thenReturn(items(BASKET_ID, 5));

            // Act & Assert
            assertEquals(5, cartService.addItem(USER_ID, BASKET_ID, 2).totalQuantity());
            verify(cartRepository, never()).countProducts(USER_ID);
        }

        @Test
        void unknownProduct_isRefused_andTheCartIsNotTouched() {
            // Arrange
            when(productClient.getProduct(BASKET_ID)).thenThrow(new ProductNotFoundException(BASKET_ID));

            // Act & Assert
            assertThrows(ProductNotFoundException.class, () -> cartService.addItem(USER_ID, BASKET_ID, 1));
            verifyNoInteractions(cartRepository);
        }

        @Test
        void goingOverTheLimitForOneProduct_takesTheAddBack() {
            // Arrange: 19 in the cart, 2 more would make 21
            when(productClient.getProduct(BASKET_ID)).thenReturn(BASKET);
            when(cartRepository.containsProduct(USER_ID, BASKET_ID)).thenReturn(true);
            when(cartRepository.addQuantity(USER_ID, BASKET_ID, 2)).thenReturn(21);

            // Act
            CartLimitExceededException exception = assertThrows(CartLimitExceededException.class,
                    () -> cartService.addItem(USER_ID, BASKET_ID, 2));

            // Assert: the same amount is subtracted again, which leaves the 19
            var order = inOrder(cartRepository);
            order.verify(cartRepository).addQuantity(USER_ID, BASKET_ID, 2);
            order.verify(cartRepository).addQuantity(USER_ID, BASKET_ID, -2);
            assertTrue(exception.getMessage().contains("already holds 19"), exception.getMessage());
        }

        @Test
        void exactlyTheLimit_isAllowed() {
            // Arrange: 18 + 2 = 20
            when(productClient.getProduct(BASKET_ID)).thenReturn(BASKET);
            when(cartRepository.containsProduct(USER_ID, BASKET_ID)).thenReturn(true);
            when(cartRepository.addQuantity(USER_ID, BASKET_ID, 2)).thenReturn(CartService.MAX_QUANTITY_PER_PRODUCT);
            when(cartRepository.findItems(USER_ID)).thenReturn(items(BASKET_ID, 20));

            // Act & Assert
            assertEquals(20, cartService.addItem(USER_ID, BASKET_ID, 2).totalQuantity());
            verify(cartRepository, never()).addQuantity(USER_ID, BASKET_ID, -2);
        }

        @Test
        void newProduct_whenTheCartIsFull_isRefused_andNothingIsWritten() {
            // Arrange
            when(productClient.getProduct(BASKET_ID)).thenReturn(BASKET);
            when(cartRepository.containsProduct(USER_ID, BASKET_ID)).thenReturn(false);
            when(cartRepository.countProducts(USER_ID)).thenReturn((long) CartService.MAX_PRODUCTS_PER_CART);

            // Act & Assert
            assertThrows(CartLimitExceededException.class, () -> cartService.addItem(USER_ID, BASKET_ID, 1));
            verify(cartRepository, never()).addQuantity(eq(USER_ID), eq(BASKET_ID), anyInt());
        }
    }

    @Nested
    @DisplayName("setItemQuantity")
    class SetItemQuantity {
        @Test
        void setsTheQuantity_whateverItWas() {
            // Arrange
            when(productClient.getProduct(BASKET_ID)).thenReturn(BASKET);
            when(cartRepository.containsProduct(USER_ID, BASKET_ID)).thenReturn(true);
            when(cartRepository.findItems(USER_ID)).thenReturn(items(BASKET_ID, 7));

            // Act
            CartResponseDto cart = cartService.setItemQuantity(USER_ID, BASKET_ID, 7);

            // Assert
            verify(cartRepository).setQuantity(USER_ID, BASKET_ID, 7);
            assertEquals(7, cart.totalQuantity());
        }

        @Test
        void productInTheCart_canChange_evenWhenTheCartIsFull() {
            // Arrange
            when(productClient.getProduct(BASKET_ID)).thenReturn(BASKET);
            when(cartRepository.containsProduct(USER_ID, BASKET_ID)).thenReturn(true);
            when(cartRepository.findItems(USER_ID)).thenReturn(items(BASKET_ID, 1));

            // Act
            cartService.setItemQuantity(USER_ID, BASKET_ID, 1);

            // Assert: the number of products is not even looked at
            verify(cartRepository, never()).countProducts(USER_ID);
            verify(cartRepository).setQuantity(USER_ID, BASKET_ID, 1);
        }

        @Test
        void newProduct_whenTheCartIsFull_isRefused() {
            // Arrange
            when(productClient.getProduct(BASKET_ID)).thenReturn(BASKET);
            when(cartRepository.containsProduct(USER_ID, BASKET_ID)).thenReturn(false);
            when(cartRepository.countProducts(USER_ID)).thenReturn((long) CartService.MAX_PRODUCTS_PER_CART);

            // Act & Assert
            assertThrows(CartLimitExceededException.class, () -> cartService.setItemQuantity(USER_ID, BASKET_ID, 1));
            verify(cartRepository, never()).setQuantity(eq(USER_ID), eq(BASKET_ID), anyInt());
        }

        @Test
        void unknownProduct_isRefused_andTheCartIsNotTouched() {
            // Arrange
            when(productClient.getProduct(BASKET_ID)).thenThrow(new ProductNotFoundException(BASKET_ID));

            // Act & Assert
            assertThrows(ProductNotFoundException.class, () -> cartService.setItemQuantity(USER_ID, BASKET_ID, 3));
            verifyNoInteractions(cartRepository);
        }
    }

    @Test
    void removeItem_removesTheProduct_withoutAskingTheCatalog() {
        // Act: removing must work for a product the catalog no longer knows, and while product-service is down
        cartService.removeItem(USER_ID, REMOVED_ID);

        // Assert
        verify(cartRepository).removeProduct(USER_ID, REMOVED_ID);
        verifyNoInteractions(productClient);
    }

    @Test
    void clearCart_deletesTheWholeCart() {
        // Act
        cartService.clearCart(USER_ID);

        // Assert
        verify(cartRepository).deleteCart(USER_ID);
    }
}
