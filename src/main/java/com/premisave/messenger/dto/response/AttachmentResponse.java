package com.premisave.messenger.dto.response;

import com.premisave.messenger.enums.AttachmentCategory;
import com.premisave.messenger.enums.MessageType;
import lombok.Data;

import java.time.LocalDateTime;

/** Returned by the upload endpoint. Send attachmentId in the message to deliver it. */
@Data
public class AttachmentResponse {
    private String attachmentId;
    private String chatId;
    private String fileName;
    private String mimeType;
    private long size;
    private AttachmentCategory category;
    private MessageType messageType;
    private LocalDateTime uploadedAt;
    /** The upload is discarded if no message claims it by this time. */
    private LocalDateTime expiresAt;
}
