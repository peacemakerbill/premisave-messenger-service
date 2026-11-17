package com.premisave.messenger.enums;

public enum MessageType {
    TEXT, IMAGE, VIDEO, DOCUMENT, VOICE, LOCATION, STICKER, REACTION,
    /** The line a call leaves in a chat. Only the call service writes these. */
    CALL
}