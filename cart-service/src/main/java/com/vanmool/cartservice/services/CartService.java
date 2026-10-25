package com.vanmool.cartservice.services;

import com.vanmool.cartservice.clients.ProductClient;
import com.vanmool.cartservice.clients.ProductDetails;
import com.vanmool.cartservice.dtos.CartItemResponseDto;
import com.vanmool.cartservice.dtos.CartLineDto;
import com.vanmool.cartservice.dtos.CartResponseDto;
import com.vanmool.cartservice.exceptions.CartLimitExceededException;
import com.vanmool.cartservice.exceptions.ProductNotFoundException;
import com.vanmool.cartservice.repositories.CartRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// The cart rules. A cart holds product ids and quantities only (CartRepository); every read asks product-service for
// the current name and price, so the customer always sees today's price. The order service will price an order
// again itself when it is placed, and never trust totals sent by a client.
@Service
public class CartService {
    public static final int MAX_QUANTITY_PER_PRODUCT = 20;
    public static final int MAX_PRODUCTS_PER_CART = 50;

    private final CartRepository cartRepository;
    private final ProductClient productClient;

    public CartService(CartRepository cartRepository, ProductClient productClient) {
        this.cartRepository = cartRepository;
        this.productClient = productClient;
    }

    public CartResponseDto getCart(UUID userId) {
        Map<UUID, Integer> items = cartRepository.findItems(userId);

        List<CartItemResponseDto> lines = new ArrayList<>();
        int totalQuantity = 0;
        BigDecimal totalPrice = BigDecimal.ZERO;
        for (Map.Entry<UUID, Integer> item : items.entrySet()) {
            UUID productId = item.getKey();
            int quantity = item.getValue();
            try {
                ProductDetails product = productClient.getProduct(productId);
                BigDecimal lineTotal = product.price().multiply(BigDecimal.valueOf(quantity));
                lines.add(new CartItemResponseDto(productId, product.name(), product.imageUrl(), product.price(),
                        quantity, lineTotal, true));
                totalQuantity += quantity;
                totalPrice = totalPrice.add(lineTotal);
            } catch (ProductNotFoundException e) {
                // Removed from the catalog since it was added: shown, but not counted, until the customer removes it
                lines.add(new CartItemResponseDto(productId, null, null, null, quantity, null, false));
            }
        }
        // A Redis hash has no order; sort by name so the cart looks the same on every read, unavailable lines last
        lines.sort(Comparator.comparing(CartItemResponseDto::name, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CartItemResponseDto::productId));
        return new CartResponseDto(lines, totalQuantity, totalPrice);
    }

    // The product ids and quantities only, in product id order, without asking product-service
    public List<CartLineDto> getItems(UUID userId) {
        return cartRepository.findItems(userId).entrySet().stream()
                .map(item -> new CartLineDto(item.getKey(), item.getValue()))
                .sorted(Comparator.comparing(CartLineDto::productId))
                .toList();
    }

    // Adds to the quantity already in the cart. The product must exist in the catalog
    public CartResponseDto addItem(UUID userId, UUID productId, int quantity) {
        productClient.getProduct(productId);
        checkRoomForProduct(userId, productId);

        // HINCRBY is atomic, so two adds at the same moment both count. If the total goes over the limit, this add is
        // taken back with a second HINCRBY, which leaves exactly the quantity there was before it
        int newQuantity = cartRepository.addQuantity(userId, productId, quantity);
        if (newQuantity > MAX_QUANTITY_PER_PRODUCT) {
            cartRepository.addQuantity(userId, productId, -quantity);
            throw new CartLimitExceededException("A cart can hold at most " + MAX_QUANTITY_PER_PRODUCT
                    + " of one product; it already holds " + (newQuantity - quantity));
        }
        return getCart(userId);
    }

    // Sets the quantity, adding the product if it is not in the cart yet. Repeating the same request changes nothing
    public CartResponseDto setItemQuantity(UUID userId, UUID productId, int quantity) {
        productClient.getProduct(productId);
        checkRoomForProduct(userId, productId);
        cartRepository.setQuantity(userId, productId, quantity);
        return getCart(userId);
    }

    // Removing a product that is not in the cart is not an error: the result is the same, the product is not there.
    // Needs only Redis, so it works even when product-service is down
    public void removeItem(UUID userId, UUID productId) {
        cartRepository.removeProduct(userId, productId);
    }

    public void clearCart(UUID userId) {
        cartRepository.deleteCart(userId);
    }

    // A product already in the cart can always change its quantity; a new one needs a free place
    private void checkRoomForProduct(UUID userId, UUID productId) {
        if (!cartRepository.containsProduct(userId, productId)
                && cartRepository.countProducts(userId) >= MAX_PRODUCTS_PER_CART) {
            throw new CartLimitExceededException("A cart can hold at most " + MAX_PRODUCTS_PER_CART + " different products");
        }
    }
}
