package com.vanmool.orderservice.dtos;

import com.vanmool.orderservice.models.ShippingAddress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// An address in India: a 10-digit mobile number (starting 6-9) for the courier, and a 6-digit PIN code
public record ShippingAddressDto(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "[6-9][0-9]{9}", message = "must be a 10-digit mobile number") String phone,
        @NotBlank @Size(max = 255) String line1,
        @Size(max = 255) String line2,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 100) String state,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{5}", message = "must be a 6-digit PIN code") String pincode) {

    public ShippingAddress toShippingAddress() {
        return new ShippingAddress(name.trim(), phone, line1.trim(), line2 == null || line2.isBlank() ? null : line2.trim(),
                city.trim(), state.trim(), pincode);
    }

    public static ShippingAddressDto from(ShippingAddress address) {
        return new ShippingAddressDto(address.getName(), address.getPhone(), address.getLine1(), address.getLine2(),
                address.getCity(), address.getState(), address.getPincode());
    }
}
