package com.vanmool.paymentservice.gateways;

// A webhook or callback whose signature does not match: not from the gateway, or changed on the way
public class InvalidSignatureException extends RuntimeException {
    public InvalidSignatureException(String message) {
        super(message);
    }
}
