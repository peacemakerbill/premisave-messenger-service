package com.premisave.messenger.entity;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "groups")
public class Group {

    @Id
    private String id;

    private String name;
    private String description;
    private String groupPhotoUrl;
    private String adminId;

    private List<String> memberIds = new ArrayList<>();
    private List<String> moderators = new ArrayList<>();

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    private boolean isActive = true;
}