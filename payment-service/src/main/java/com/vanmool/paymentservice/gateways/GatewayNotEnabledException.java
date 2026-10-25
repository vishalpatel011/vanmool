package com.vanmool.paymentservice.gateways;

import com.vanmool.paymentservice.models.GatewayName;

public class GatewayNotEnabledException extends RuntimeException {
    public GatewayNotEnabledException(GatewayName gateway) {
        super("The payment gateway " + gateway + " is not available");
    }
}
