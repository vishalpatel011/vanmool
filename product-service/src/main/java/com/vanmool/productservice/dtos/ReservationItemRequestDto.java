package com.vanmool.productservice.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ReservationItemRequestDto {
    @NotNull
    private UUID productId;

    @NotNull
    @Min(1)
    @Max(1000)
    private Integer quantity;
}
