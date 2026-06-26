package com.premisave.messenger.exception;

@SuppressWarnings("serial")
public class ChatNotFoundException extends RuntimeException {
    public ChatNotFoundException(String message) {
        super(message);
    }
}