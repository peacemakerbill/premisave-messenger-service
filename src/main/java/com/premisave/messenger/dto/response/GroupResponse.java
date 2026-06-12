package com.premisave.messenger.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class GroupResponse {
    private String id;
    private String name;
    private String description;
    private String groupPhotoUrl;
    private String adminId;
    private List<String> memberIds;
    private LocalDateTime createdAt;
}