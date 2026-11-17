package com.premisave.messenger.entity;

import com.premisave.messenger.enums.NotificationType;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Something that happened to a person: made an admin, made the owner, added to or removed from a group.
 * Shown in the app's notification list and also sent by email. Old ones are deleted after 180 days.
 */
@Data
@Document(collection = "notifications")
@CompoundIndex(name = "notifications_user_idx", def = "{'userId': 1, 'createdAt': -1}")
public class UserNotification {

    @Id
    private String id;

    /** The person it is for. */
    private String userId;

    private NotificationType type;
    private String title;
    private String body;

    /** The group it is about. */
    private String groupId;

    /** The group's chat, so the app can open it. Null when the person can no longer open it. */
    private String chatId;

    /** Who did it. */
    private String actorId;
    private String actorName;

    @Indexed(name = "notifications_ttl_idx", expireAfterSeconds = 15552000)
    private LocalDateTime createdAt;

    private boolean read;
    private LocalDateTime readAt;
}
