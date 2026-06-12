package com.premisave.messenger.dto.request;

import com.premisave.messenger.enums.MessageType;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SendMessageRequest {

    @NotBlank
    private String chatId;

    private String content;

    private MessageType messageType = MessageType.TEXT;

    private String mediaUrl;
    private String fileName;
    private Long fileSize;

    // For location sharing
    private Double latitude;
    private Double longitude;
}