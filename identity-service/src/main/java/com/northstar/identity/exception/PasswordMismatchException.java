package com.northstar.identity.exception;

public class PasswordMismatchException extends RuntimeException {
    public PasswordMismatchException() {
        super("Password and confirm password must match");
    }
}
