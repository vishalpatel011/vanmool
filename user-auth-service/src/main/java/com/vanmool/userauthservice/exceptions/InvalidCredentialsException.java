package com.vanmool.userauthservice.exceptions;

// One message for an unknown email and for a wrong password, so a caller cannot find out which emails have accounts
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Email or password is incorrect");
    }
}
