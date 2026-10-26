package com.vanmool.orderservice.dtos;

// How the order was sent: the customer follows it on the carrier's site with the tracking number (if there is one)
public record ShipmentDto(String carrier, String trackingNumber) {
}
