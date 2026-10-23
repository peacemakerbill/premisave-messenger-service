package com.premisave.messenger.entity;

import com.premisave.messenger.enums.AttachmentCategory;
import com.premisave.messenger.enums.AttachmentStatus;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "attachments")
@CompoundIndex(name = "status_created_idx", def = "{'status': 1, 'createdAt': 1}")
public class Attachment {

    @Id
    private String id;

    @Indexed
    private String chatId;

    private String uploaderId;

    /** Original name as shown to users. Never used to build a path. */
    private String fileName;

    private String mimeType;
    private long size;
    private String sha256;
    private AttachmentCategory category;

    /** Path relative to the storage base directory. */
    private String storagePath;

    private AttachmentStatus status = AttachmentStatus.UPLOADED;

    private String messageId;

    private LocalDateTime createdAt;
    private LocalDateTime attachedAt;
}
