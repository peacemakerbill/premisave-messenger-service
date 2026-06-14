package com.premisave.messenger.entity;

import com.premisave.messenger.enums.MessageStatus;
import com.premisave.messenger.enums.MessageType;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "messages")
@CompoundIndexes({
    @CompoundIndex(name = "chat_sender_idx", def = "{'chatId': 1, 'senderId': 1}"),
    @CompoundIndex(name = "chat_timestamp_idx", def = "{'chatId': 1, 'createdAt': -1}")
})
public class Message {

    @Id
    private String id;

    private String chatId;
    private String senderId;
    private String receiverId; // For private chats

    private MessageType messageType = MessageType.TEXT;
    private String content;           // Text or media URL
    private String mediaUrl;          // For images, videos, etc.
    private String fileName;
    private Long fileSize;

    private MessageStatus status = MessageStatus.SENT;

    private List<String> readBy = new ArrayList<>(); // List of userIds who read it

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime editedAt;
    private boolean isDeleted = false;
    private boolean isDeletedForEveryone = false;
    
    private boolean isActive = true;   // For soft delete / visibility control
}