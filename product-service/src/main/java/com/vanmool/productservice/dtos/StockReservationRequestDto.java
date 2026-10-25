package com.vanmool.productservice.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

// POST /stock-reservations, sent by order-service when an order is placed. The orderId makes the request safe to repeat
@Getter
@Setter
public class StockReservationRequestDto {
    @NotNull
    private UUID orderId;

    @NotEmpty
    @Size(max = 50)
    private List<@Valid @NotNull ReservationItemRequestDto> items;
}
