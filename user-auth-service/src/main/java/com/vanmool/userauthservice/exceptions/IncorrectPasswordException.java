package com.vanmool.userauthservice.exceptions;

// The current password given to change it is wrong. 403, not 401: the token is fine, and a client that sees 401 would
// sign the user out
public class IncorrectPasswordException extends RuntimeException {
    public IncorrectPasswordException() {
        super("The current password is not correct");
    }
}
