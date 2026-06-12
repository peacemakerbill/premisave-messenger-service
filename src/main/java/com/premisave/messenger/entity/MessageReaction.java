package com.premisave.messenger.entity;

import com.premisave.messenger.enums.ReactionType;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "message_reactions")
public class MessageReaction {

    @Id
    private String id;

    private String messageId;
    private String userId;
    private ReactionType reactionType;

    private LocalDateTime createdAt;
}