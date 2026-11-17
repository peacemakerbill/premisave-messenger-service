package com.premisave.messenger.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** A message written in a call's own chat. Kept so people who were in the call can read it afterwards. */
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

    private LocalDateTime createdAt;
}
