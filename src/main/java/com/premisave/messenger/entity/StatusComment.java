package com.premisave.messenger.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "status_comments")
@CompoundIndex(name = "status_comment_idx", def = "{'statusId': 1, 'createdAt': 1}")
public class StatusComment {

    @Id
    private String id;

    private String statusId;
    private String authorId;
    private String content;
    private LocalDateTime createdAt;
}