package com.premisave.messenger.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "user_presence")
public class UserPresence {

    @Id
    private String userId;

    private boolean isOnline = false;
    private LocalDateTime lastSeen;
    private String currentChatId; // If user is in a specific chat
}