package com.premisave.messenger.entity;

import com.premisave.messenger.enums.ChatType;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Document(collection = "chats")
public class Chat {

    @Id
    private String id;

    private ChatType chatType;

    // For private chat: two participants - always the real user ID (never email)
    private List<String> participantIds = new ArrayList<>();

    // Maps userId -> email, kept alongside participantIds for quick lookup/display
    // without needing a round trip to auth-service for every render.
    private Map<String, String> participantEmails = new HashMap<>();

    // For group chat
    private String groupId;

    private String lastMessageId;
    private LocalDateTime lastMessageAt;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    private boolean isActive = true;

    // Group-specific metadata cached in chat
    private String groupName;
    private String groupPhotoUrl;
}