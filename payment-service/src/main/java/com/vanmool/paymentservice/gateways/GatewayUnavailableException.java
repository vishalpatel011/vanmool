package com.vanmool.paymentservice.gateways;

// The gateway could not be reached or refused the request
public class GatewayUnavailableException extends RuntimeException {
    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
