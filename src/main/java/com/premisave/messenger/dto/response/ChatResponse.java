package com.premisave.messenger.dto.response;

import com.premisave.messenger.enums.ChatType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ChatResponse {

    private String id;
    private ChatType chatType;

    private List<String> participantIds;
    private String groupId;

    private MessageResponse lastMessage;
    private LocalDateTime lastMessageAt;

    private int unreadCount;
    private boolean isOnline; // For the other participant in private chat
}