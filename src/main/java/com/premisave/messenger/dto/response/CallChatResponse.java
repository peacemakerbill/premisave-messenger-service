package com.premisave.messenger.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallChatResponse {
    private String id;
    private String callId;
    private String senderId;
    private String senderName;
    private String text;
    private LocalDateTime createdAt;
}
