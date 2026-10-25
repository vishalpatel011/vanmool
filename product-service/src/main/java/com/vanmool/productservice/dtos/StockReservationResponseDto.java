package com.vanmool.productservice.dtos;

import com.vanmool.productservice.models.ReservationStatus;
import com.vanmool.productservice.models.StockReservation;

import java.util.List;
import java.util.UUID;

// The reservation with the name and price of every product at the moment it was reserved: order-service places the
// order at exactly these prices
public record StockReservationResponseDto(UUID orderId, ReservationStatus status, List<ReservedItemResponseDto> items) {
    public static StockReservationResponseDto from(StockReservation reservation) {
        return new StockReservationResponseDto(reservation.getOrderId(), reservation.getStatus(),
                reservation.getItems().stream().map(ReservedItemResponseDto::from).toList());
    }
}
