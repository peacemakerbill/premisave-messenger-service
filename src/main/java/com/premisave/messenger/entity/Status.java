package com.premisave.messenger.entity;

import com.premisave.messenger.enums.StatusType;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A status post. It is visible to the owner's followers until expiresAt, then it
 * moves to the owner's past statuses. It is only removed when the owner deletes it.
 */
@Data
@Document(collection = "statuses")
@CompoundIndexes({
    @CompoundIndex(name = "status_owner_expiry_idx", def = "{'ownerId': 1, 'expiresAt': -1}"),
    @CompoundIndex(name = "status_expiry_idx", def = "{'expiresAt': 1}")
})
public class Status {

    @Id
    private String id;

    private String ownerId;
    private StatusType type = StatusType.TEXT;

    // Text statuses: the words and the background colour they sit on
    private String text;
    private String backgroundColor;

    // Photo, video and audio statuses: an optional caption and the stored file
    private String caption;
    private String attachmentId;
    private String fileName;
    private String mimeType;
    private Long fileSize;

    // How long the owner chose, in hours, and the moment it disappears
    private int durationHours;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    // Totals kept in step with status_activity and status_comments
    private long viewCount;
    private long likeCount;
    private long reactionCount;
    private long commentCount;
}