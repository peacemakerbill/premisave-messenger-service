package com.premisave.messenger.dto.websocket;

import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.enums.MessageType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    private String id;
    private String chatId;
    private String senderId;
    private String receiverId;
    private String content;
    private MessageType messageType;
    private String mediaUrl;
    private LocalDateTime timestamp;
    private String status;

    // Reply Support
    private String replyToMessageId;

    private MessageResponse.ReplyPreview replyPreview;
}