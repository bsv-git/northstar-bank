package com.northstar.identity.exception;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String email) {
        super("No pending user was found for email: " + email);
    }
}
