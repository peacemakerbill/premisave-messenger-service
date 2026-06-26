package com.premisave.messenger.exception;

@SuppressWarnings("serial")
public class MessageNotFoundException extends RuntimeException {
    public MessageNotFoundException(String message) {
        super(message);
    }
}