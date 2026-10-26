package com.vanmool.userauthservice.exceptions;

// One answer for an unknown, used and expired link alike: the reason is no use to someone trying links
public class InvalidResetLinkException extends RuntimeException {
    public InvalidResetLinkException() {
        super("This password reset link is not valid any more; please ask for a new one");
    }
}
