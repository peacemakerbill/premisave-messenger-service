package com.premisave.messenger.entity;

import com.premisave.messenger.enums.ChatType;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "chats")
public class Chat {

    @Id
    private String id;

    private ChatType chatType;

    // For private chat: two participants
    private List<String> participantIds = new ArrayList<>();

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