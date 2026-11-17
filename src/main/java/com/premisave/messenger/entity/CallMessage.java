package com.premisave.messenger.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** A message written in a call's own chat. Kept for 30 days, then deleted. */
@Data
@Document(collection = "call_messages")
@CompoundIndex(name = "call_messages_call_idx", def = "{'callId': 1, 'createdAt': 1}")
public class CallMessage {

    @Id
    private String id;
    private String callId;
    private String senderId;
    private String senderName;
    private String text;

    @Indexed(name = "call_messages_ttl_idx", expireAfter = "30d")
    private LocalDateTime createdAt;
}
