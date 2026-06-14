package com.premisave.messenger.dto.request;

import com.premisave.messenger.enums.MessageType;
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
    private MultipartFile file;

    // Populated after successful upload
    private String mediaUrl;
    private String fileName;
    private Long fileSize;

    // Reply support
    private String replyToMessageId;

    // Location sharing
    private Double latitude;
    private Double longitude;
}