package com.vanmool.userauthservice.exceptions;

public class PasswordUnchangedException extends RuntimeException {
    public PasswordUnchangedException() {
        super("newPassword: must be different from the current password");
    }
}
