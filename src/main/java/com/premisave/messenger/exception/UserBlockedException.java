package com.premisave.messenger.exception;

@SuppressWarnings("serial")
public class UserBlockedException extends RuntimeException {
    public UserBlockedException(String message) {
        super(message);
    }
}