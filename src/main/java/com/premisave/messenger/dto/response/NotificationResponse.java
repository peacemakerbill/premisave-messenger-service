package com.premisave.messenger.dto.response;

import com.premisave.messenger.enums.NotificationType;
import lombok.Data;

import java.time.LocalDateTime;

/** One notification, in the list and in the live push to the app. */
@Data
public class NotificationResponse {

    private String id;
    private NotificationType type;
    private String title;
    private String body;
    private String groupId;
    private String chatId;
    private String actorId;
    private String actorName;
    private LocalDateTime createdAt;
    private boolean read;
}
