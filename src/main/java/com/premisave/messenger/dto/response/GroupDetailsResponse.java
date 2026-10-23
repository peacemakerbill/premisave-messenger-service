package com.premisave.messenger.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A group with its chat id and its members resolved to profiles.
 * adminId is the owner; moderators are members promoted to admin.
 */
@Data
public class GroupDetailsResponse {

    private String id;
    private String chatId;
    private String name;
    private String description;
    private String groupPhotoUrl;
    private String adminId;
    private List<String> moderators = new ArrayList<>();
    private List<String> memberIds = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<Member> members = new ArrayList<>();

    @Data
    public static class Member {
        private String id;
        private String displayName;
        private String username;
        private String email;
        private String firstName;
        private String lastName;
        private String profilePictureUrl;
        /** OWNER, ADMIN or MEMBER */
        private String role;
    }
}