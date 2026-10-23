package com.premisave.messenger.dto.request;

import com.premisave.messenger.enums.MessageType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class SendMessageRequest {

    @NotBlank(message = "Chat ID is required")
    private String chatId;

    private String content;

    private MessageType messageType = MessageType.TEXT;

    // === File Upload Support ===
    // Either attach a file directly (sent and uploaded in one request)...
    @JsonIgnore
    private MultipartFile file;

    // ...or send the id returned by POST /api/attachments.
    private String attachmentId;

    // Populated after successful upload
    private String mediaUrl;
    private String fileName;
    private Long fileSize;

    // Reply support
    private String replyToMessageId;

    // Users tagged with @ in the text
    private java.util.List<String> mentionedUserIds;

    // Location sharing
    private Double latitude;
    private Double longitude;
}