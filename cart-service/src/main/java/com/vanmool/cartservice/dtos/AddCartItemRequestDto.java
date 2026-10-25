package com.vanmool.cartservice.dtos;

import com.vanmool.cartservice.services.CartService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

// POST /cart/items: add this many of a product to the cart, on top of what is already there
@Getter
@Setter
public class AddCartItemRequestDto {
    @NotNull
    private UUID productId;

    @NotNull
    @Min(1)
    @Max(CartService.MAX_QUANTITY_PER_PRODUCT)
    private Integer quantity;
}
