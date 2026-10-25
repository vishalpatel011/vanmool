package com.vanmool.cartservice.dtos;

import com.vanmool.cartservice.services.CartService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

// PUT /cart/items/{productId}: the quantity the cart should hold. Removing a product is DELETE, not quantity 0
@Getter
@Setter
public class UpdateCartItemRequestDto {
    @NotNull
    @Min(1)
    @Max(CartService.MAX_QUANTITY_PER_PRODUCT)
    private Integer quantity;
}
