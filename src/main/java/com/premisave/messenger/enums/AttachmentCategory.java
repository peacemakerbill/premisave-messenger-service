package com.premisave.messenger.enums;

/**
 * Coarse type of an uploaded file. Drives how clients render it and which
 * MessageType the resulting message gets. Anything that is not an image,
 * video or audio is a DOCUMENT, so every file type is accepted.
 */
public enum AttachmentCategory {
    IMAGE(MessageType.IMAGE),
    VIDEO(MessageType.VIDEO),
    AUDIO(MessageType.VOICE),
    DOCUMENT(MessageType.DOCUMENT);

    private final MessageType messageType;

    AttachmentCategory(MessageType messageType) {
        this.messageType = messageType;
    }

    public MessageType toMessageType() {
        return messageType;
    }
}
