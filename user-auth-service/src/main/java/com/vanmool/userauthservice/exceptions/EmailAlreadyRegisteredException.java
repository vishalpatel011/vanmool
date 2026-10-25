package com.vanmool.userauthservice.exceptions;

public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException(String email) {
        super("An account with the email " + email + " already exists");
    }
}
