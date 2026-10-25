package com.vanmool.orderservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Where the order goes, copied into the order: changing an address book later must not move an order already placed.
// Stored in the orders table itself (an embeddable), as the shipping_* columns
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class ShippingAddress {
    @Column(name = "shipping_name", nullable = false, length = 100)
    private String name;
    @Column(name = "shipping_phone", nullable = false, length = 10)
    private String phone;
    @Column(name = "shipping_line1", nullable = false)
    private String line1;
    @Column(name = "shipping_line2")
    private String line2;
    @Column(name = "shipping_city", nullable = false, length = 100)
    private String city;
    @Column(name = "shipping_state", nullable = false, length = 100)
    private String state;
    @Column(name = "shipping_pincode", nullable = false, length = 6)
    private String pincode;
}
