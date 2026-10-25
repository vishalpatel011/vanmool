package com.vanmool.productservice.models;

public enum ReservationStatus {
    // The order holds the units; nobody else can buy them
    RESERVED,
    // The units went back to the stock (the order was cancelled or could not be placed)
    RELEASED
}
