package com.premisave.messenger.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "group_members")
public class GroupMember {

    @Id
    private String id;

    private String groupId;
    private String userId;
    private String role; // "ADMIN", "MODERATOR", "MEMBER"
    private LocalDateTime joinedAt;
    private boolean isMuted = false;
}