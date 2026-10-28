package com.vanmool.cartservice.controllers;

import com.vanmool.cartservice.dtos.AddCartItemRequestDto;
import com.vanmool.cartservice.dtos.CartLineDto;
import com.vanmool.cartservice.dtos.CartResponseDto;
import com.vanmool.cartservice.dtos.UpdateCartItemRequestDto;
import com.vanmool.cartservice.exceptions.NotAUserTokenException;
import com.vanmool.cartservice.services.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// The cart of the signed-in user. There is no user id in any path: it comes from the token's subject, so a user can
// only ever reach their own cart. Adding and changing answer with the whole updated cart, priced, to save a second
// request. Removing answers 204 without a body: it needs only Redis, so it succeeds even when product-service is down.
@RestController
@RequestMapping("/cart")
@Tag(name = "Cart", description = "The signed-in customer's cart; prices come live from the product service")
public class CartController {
    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @Operation(summary = "The cart, with current prices and totals")
    @GetMapping
    public CartResponseDto getCart(@AuthenticationPrincipal Jwt jwt) {
        return cartService.getCart(userId(jwt));
    }

    // Only ids and quantities, from Redis alone: for checkout, which takes its prices from the stock reservation
    @Operation(summary = "Only the product ids and quantities, from Redis (used at checkout)")
    @GetMapping("/items")
    public List<CartLineDto> getItems(@AuthenticationPrincipal Jwt jwt) {
        return cartService.getItems(userId(jwt));
    }

    // Not idempotent: each request adds its quantity again (an "Add to cart" button)
    @Operation(summary = "Add a quantity of a product")
    @PostMapping("/items")
    public CartResponseDto addItem(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddCartItemRequestDto request) {
        return cartService.addItem(userId(jwt), request.getProductId(), request.getQuantity());
    }

    // Idempotent: sets the quantity, whatever it was (a quantity field in the cart page)
    @Operation(summary = "Set a product's quantity, adding the product if it is missing")
    @PutMapping("/items/{productId}")
    public CartResponseDto setItemQuantity(@AuthenticationPrincipal Jwt jwt, @PathVariable("productId") UUID productId,
                                           @Valid @RequestBody UpdateCartItemRequestDto request) {
        return cartService.setItemQuantity(userId(jwt), productId, request.getQuantity());
    }

    @Operation(summary = "Remove a product")
    @DeleteMapping("/items/{productId}")
    public ResponseEntity<Void> removeItem(@AuthenticationPrincipal Jwt jwt, @PathVariable("productId") UUID productId) {
        cartService.removeItem(userId(jwt), productId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Empty the cart")
    @DeleteMapping
    public ResponseEntity<Void> clearCart(@AuthenticationPrincipal Jwt jwt) {
        cartService.clearCart(userId(jwt));
        return ResponseEntity.noContent().build();
    }

    // user-auth-service puts the user's id in the token's subject ("sub")
    private static UUID userId(Jwt jwt) {
        String subject = jwt.getSubject();
        try {
            return UUID.fromString(subject == null ? "" : subject);
        } catch (IllegalArgumentException e) {
            throw new NotAUserTokenException();
        }
    }
}
