package com.premisave.messenger.dto.response;

import com.premisave.messenger.enums.MessageStatus;
import com.premisave.messenger.enums.MessageType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MessageResponse {

    private String id;
    private String chatId;
    private String senderId;
    private String senderName;
    private String senderProfilePic;

    private MessageType messageType;
    private String content;
    private String mediaUrl;
    private String fileName;

    private MessageStatus status;
    private List<String> readBy;

    private LocalDateTime createdAt;
    private LocalDateTime editedAt;

    private boolean isDeleted;
    private boolean isDeletedForEveryone;

    // Reply support
    private String replyToMessageId;

    private ReplyPreview replyPreview;

    @Data
    public static class ReplyPreview {
        private String messageId;
        private String senderId;
        private String senderName;
        private String content;           // Truncated preview
        private MessageType messageType;
        private String mediaUrl;          // For image/video reply preview
    }
}